package com.nexxserve.cavgogateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    // Comma-separated list of allowed origins, or "*" to allow all.
    // Example: CORS_ALLOWED_ORIGINS=https://book.gocavgo.com,https://admin.gocavgo.com
    // Example: CORS_ALLOWED_ORIGINS=*
    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        if ("*".equals(allowedOrigins)) {
            // Use allowedOriginPattern to allow all while still honoring credentials.
            corsConfig.addAllowedOriginPattern("*");
        } else {
            // Explicit origins from the comma-separated env var.
            for (String origin : allowedOrigins.split(",")) {
                corsConfig.addAllowedOriginPattern(origin.strip());
            }
        }

        corsConfig.addAllowedMethod("GET");
        corsConfig.addAllowedMethod("POST");
        corsConfig.addAllowedMethod("PUT");
        corsConfig.addAllowedMethod("DELETE");
        corsConfig.addAllowedMethod("PATCH");
        corsConfig.addAllowedMethod("HEAD");
        corsConfig.addAllowedMethod("OPTIONS");

        corsConfig.addAllowedHeader("*");
        corsConfig.setAllowCredentials(true);

        // Expose common headers for SSE (e.g. Last-Event-ID) and auth.
        corsConfig.addExposedHeader("*");

        // Cache preflight response for 1 hour.
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}
