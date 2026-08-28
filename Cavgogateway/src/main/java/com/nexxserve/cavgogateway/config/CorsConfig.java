package com.nexxserve.cavgogateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        if ("*".equals(allowedOrigins)) {
            corsConfig.addAllowedOriginPattern("*");
        } else {
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
        corsConfig.addExposedHeader("*");
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        logger.info("🔑 CORS CorsWebFilter created — allowed origins: {}", allowedOrigins);
        return new CorsWebFilter(source);
    }
}
