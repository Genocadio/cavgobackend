package com.nexxserve.cavgomain.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.UserRepository;
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
 * Spring authorities.
 *
 * <p>The local user row is the profile/business mirror, not the auth decision:
 * a verified token authenticates the request even when no local row exists yet
 * (the {@code syncUser} mutation then provisions it). A local row marked
 * DISABLED blocks the request even with a still-valid token.
 */
@Component
@RequiredArgsConstructor
public class NexxauthJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(NexxauthJwtAuthenticationFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final NexxauthJwtVerifier jwtVerifier;
    private final UserRepository userRepository;

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

        // Skip re-authentication if a valid security context already exists
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // A local DISABLED row blocks the request even with a valid token
        var localUser = userRepository.findById(claims.userId()).orElse(null);
        if (localUser != null && localUser.getStatus() == UserStatus.INACTIVE) {
            log.warn("Nexxauth token presented for disabled userId={}", claims.userId());
            writeUnauthorized(response, JwtAuthenticationException.Reason.USER_DISABLED.name(), "User account is disabled");
            return;
        }

        // Map token role names to local Spring authorities
        var authorities = claims.roles().stream()
                .map(NexxauthRoles::fromNexxauthName)
                .filter(role -> role != null)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                claims.userId().toString(), token, authorities
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.info("Nexxauth token verified for userId={}, roles={}", claims.userId(), authorities);
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
