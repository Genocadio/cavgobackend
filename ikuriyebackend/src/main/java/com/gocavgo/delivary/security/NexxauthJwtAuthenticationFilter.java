package com.gocavgo.delivary.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.service.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Authenticates every request that carries a Nexxauth org-access token
 * ({@code Authorization: Bearer <jwt>}). The token is verified offline against
 * the organisation's public key and its {@code roles} claim is mapped to local
 * Spring authorities — i.e. permissions are verified from the token, and a role
 * change in Nexxauth takes effect as soon as the user gets a new token.
 *
 * <p>The local {@code users} row is the profile/business mirror, not the auth
 * decision: a verified token authenticates the request even when no local row
 * exists yet (the {@code syncUser} mutation then provisions it). A local row
 * marked DISABLED blocks the request even with a still-valid token.
 */
@Component
@RequiredArgsConstructor
public class NexxauthJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NexxauthJwtAuthenticationFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final NexxauthJwtVerifier jwtVerifier;
    private final UserRepository userRepository;
    private final UserService userService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        var token = authHeader.substring(7);

        NexxauthJwtVerifier.NexxauthClaims claims;
        try {
            claims = jwtVerifier.verify(token);
        } catch (JwtAuthenticationException e) {
            log.warn("Nexxauth token verification failed: {} - {}", e.getReason(), e.getMessage());
            writeUnauthorized(response, e.getReason().name(), e.getMessage());
            return;
        }

        request.setAttribute("nexxauthUserId", claims.userId());
        request.setAttribute("nexxauthClaims", claims);
        request.setAttribute("nexxauthDataHash", claims.dataHash());
        request.setAttribute("nexxauthRoles", claims.roles());

        // Skip re-authentication if a valid security context already exists
        // (e.g. forwarded requests within the same thread).
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // ── DataHash-based inline sync ───────────────────────────────────────
        // On every authenticated request, check whether the user exists locally
        // and whether the JWT's dataHash matches the stored one. If not, sync
        // from Nexxauth BEFORE the request proceeds so that resolvers always
        // operate on up-to-date user data (roles, profile, status).
        var localUser = userRepository.findById(claims.userId()).orElse(null);
        boolean needsSync = localUser == null
                || claims.dataHash() == null
                || !claims.dataHash().equals(localUser.getDataHash());

        if (needsSync) {
            String reason = localUser == null ? "user not found locally"
                    : claims.dataHash() == null ? "token has no dataHash"
                    : "hash mismatch";
            log.info("Inline sync: userId={} ({})", claims.userId(), reason);
            try {
                userService.syncUser(claims.userId(), claims.dataHash());
                // Re-read after sync to pick up the potentially updated status.
                localUser = userRepository.findById(claims.userId()).orElse(null);
            } catch (Exception e) {
                log.error("Inline sync failed for userId={}: {}", claims.userId(), e.getMessage());
                if (localUser == null) {
                    // User doesn't exist and we can't provision them — block.
                    writeUnauthorized(response, "SYNC_FAILED",
                            "Could not provision user profile: " + e.getMessage());
                    return;
                }
                // User exists but sync failed — proceed with stale data rather
                // than blocking all requests when Nexxauth is temporarily down.
            }
        }

        // A local DISABLED row blocks the request even with a valid token.
        if (localUser != null && "DISABLED".equals(localUser.getStatus().name())) {
            log.warn("Nexxauth token presented for disabled userId={}", claims.userId());
            writeUnauthorized(response, JwtAuthenticationException.Reason.USER_DISABLED.name(), "User account is disabled");
            return;
        }

        // Map the token's role names to local Spring authorities. Multiple roles
        // grant multiple ROLE_* authorities (e.g. ["worker", "driver"]).
        var authorities = claims.roles().stream()
                .map(NexxauthRoles::fromNexxauthName)
                .filter(role -> role != null)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                claims.userId().toString(), token, authorities
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.debug("Nexxauth token verified for userId={}, roles={}, synced={}",
                claims.userId(), authorities, needsSync);
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "errors", Map.of(
                        "message", message,
                        "extensions", Map.of("code", code)
                )
        ));
    }
}
