package com.gocavgo.delivary.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.annotation.PostConstruct;

/**
 * Ensures the SecurityContext is available on WebSocket subscription handler
 * threads.  In Spring Boot 4.x the default {@code SecurityContextHolder}
 * strategy is {@code MODE_THREADLOCAL}, which means subscription resolvers
 * running on Reactor/Netty threads see an empty context.
 *
 * <p>Setting {@code MODE_INHERITABLETHREADLOCAL} causes child threads
 * (including the WebSocket executor) to inherit the parent's
 * SecurityContext — which was established during the HTTP upgrade handshake
 * by {@code NexxauthJwtAuthenticationFilter}.
 */
@Configuration
public class WebSocketSecurityConfig {

    @PostConstruct
    public void configureSecurityContext() {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }
}
