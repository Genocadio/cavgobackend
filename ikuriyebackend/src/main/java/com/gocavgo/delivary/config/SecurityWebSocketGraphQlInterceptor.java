package com.gocavgo.delivary.config;

import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.security.NexxauthJwtVerifier;
import com.gocavgo.delivary.security.NexxauthRoles;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Intercepts WebSocket and HTTP GraphQL requests to authenticate connection_init
 * payloads and establish the SecurityContext in reactive pipelines.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityWebSocketGraphQlInterceptor implements WebSocketGraphQlInterceptor {

    private final NexxauthJwtVerifier jwtVerifier;
    private final UserRepository userRepository;

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketGraphQlSession session, Map<String, Object> connectionInitPayload) {
        if (connectionInitPayload == null || connectionInitPayload.isEmpty()) {
            log.debug("WebSocket connection_init without payload");
            return Mono.empty();
        }

        Object authVal = connectionInitPayload.get("Authorization");
        if (authVal == null) {
            authVal = connectionInitPayload.get("authorization");
        }

        if (authVal instanceof String authStr && !authStr.isBlank()) {
            String token = authStr.startsWith("Bearer ") ? authStr.substring(7).trim() : authStr.trim();
            try {
                NexxauthJwtVerifier.NexxauthClaims claims = jwtVerifier.verify(token);

                var localUser = userRepository.findById(claims.userId()).orElse(null);
                if (localUser != null && "DISABLED".equals(localUser.getStatus().name())) {
                    log.warn("WebSocket connection_init: user disabled userId={}", claims.userId());
                    return Mono.error(new AccessDeniedException("User account is disabled"));
                }

                var authorities = claims.roles().stream()
                        .map(NexxauthRoles::fromNexxauthName)
                        .filter(role -> role != null)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                        .toList();

                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        claims.userId().toString(), token, authorities
                );
                var securityContext = new SecurityContextImpl(authentication);

                session.getAttributes().put(SecurityContext.class.getName(), securityContext);
                session.getAttributes().put("AUTHENTICATION", authentication);

                log.info("WebSocket connection_init authenticated for userId={}, roles={}", claims.userId(), authorities);
            } catch (Exception e) {
                log.warn("WebSocket connection_init authentication failed: {}", e.getMessage());
                return Mono.error(new AccessDeniedException("WebSocket authentication failed: " + e.getMessage()));
            }
        }

        return Mono.empty();
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        // 1. Check session attributes (from WebSocket connection_init)
        SecurityContext context = (SecurityContext) request.getAttributes().get(SecurityContext.class.getName());
        if (context == null && request.getAttributes().containsKey("AUTHENTICATION")) {
            Authentication auth = (Authentication) request.getAttributes().get("AUTHENTICATION");
            if (auth != null) {
                context = new SecurityContextImpl(auth);
            }
        }

        if (context != null && context.getAuthentication() != null) {
            SecurityContext finalContext = context;
            return chain.next(request)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(finalContext)));
        }

        // 2. Check if SecurityContextHolder was set by HTTP filter (during HTTP upgrade or POST)
        Authentication httpAuth = SecurityContextHolder.getContext().getAuthentication();
        if (httpAuth != null && httpAuth.isAuthenticated()) {
            var httpContext = new SecurityContextImpl(httpAuth);
            return chain.next(request)
                    .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(httpContext)));
        }

        return chain.next(request);
    }
}
