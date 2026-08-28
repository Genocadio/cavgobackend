package com.nexxserve.cavgogateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * CORS is now handled by spring.cloud.gateway.globalcors in application.yml / application-docker.yml.
 * This class only logs the resolved CORS configuration at startup for debugging.
 */
@Configuration
public class CorsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Value("${cors.allowed-origins:*}")
    private String allowedOrigins;

    @jakarta.annotation.PostConstruct
    public void logCorsConfig() {
        logger.info("🔑 CORS allowed-origins resolved to: {}", allowedOrigins);
    }
}
