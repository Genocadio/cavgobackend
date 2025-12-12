package com.nexxserve.cavgogateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        // Specify allowed origins explicitly for better security and compatibility
        corsConfig.addAllowedOriginPattern("https://book.gocavgo.com");
        corsConfig.addAllowedOriginPattern("https://admin.gocavgo.com");
        corsConfig.addAllowedOriginPattern("http://localhost:3000"); // For local development
//        corsConfig.addAllowedOriginPattern("http://localhost:8080"); // For local development

        // Allow all methods including OPTIONS for preflight
        corsConfig.addAllowedMethod("GET");
        corsConfig.addAllowedMethod("POST");
        corsConfig.addAllowedMethod("PUT");
        corsConfig.addAllowedMethod("DELETE");
        corsConfig.addAllowedMethod("OPTIONS");
        corsConfig.addAllowedMethod("HEAD");
        corsConfig.addAllowedMethod("PATCH");

        // Allow all headers
        corsConfig.addAllowedHeader("*");

        // Allow credentials
        corsConfig.setAllowCredentials(true);

        // Expose common headers for SSE
        corsConfig.addExposedHeader("*");

        // Cache preflight response for 1 hour
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}