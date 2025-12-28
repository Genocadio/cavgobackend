package com.gocavgo.Navigation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Only enable CORS in non-production environments
        if (!isProduction()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(
                            "http://localhost:3000",
                            "http://localhost:3001",
                            "http://localhost:5173",
                            "http://localhost:5174",
                            "http://localhost:8080",
                            "http://localhost:8081",
                            "http://127.0.0.1:3000",
                            "http://127.0.0.1:3001",
                            "http://127.0.0.1:5173",
                            "http://127.0.0.1:5174",
                            "http://127.0.0.1:8080",
                            "http://127.0.0.1:8081"
                    )
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                    .allowedHeaders("*")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }

    private boolean isProduction() {
        return activeProfile != null && 
               (activeProfile.contains("prod") || activeProfile.contains("production"));
    }
}

