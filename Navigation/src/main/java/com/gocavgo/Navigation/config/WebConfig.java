package com.gocavgo.Navigation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve Next.js static assets at their absolute paths (/_next/static/..., /icon.svg, etc.)
        // These are required by the Next.js app regardless of where it's served from
        registry.addResourceHandler("/_next/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // resourcePath will be like "static/chunks/..." for /_next/static/chunks/...
                        // We need to prepend "_next/" to get the correct path
                        String actualPath = "_next/" + resourcePath;
                        Resource requestedResource = location.createRelative(actualPath);
                        
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        
                        return null;
                    }
                });
        
        // Serve static assets (icons, images, etc.)
        registry.addResourceHandler("/icon*", "/apple-icon.png", "/placeholder*")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
        
        // Serve the frontend app from root (/) and handle all client-side routes
        // This catch-all handler serves index.html for all routes except /api/**
        // Note: Controllers (like /api/**) are checked BEFORE resource handlers by Spring MVC
        // So API routes will never reach this handler
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // Skip API routes - let controllers handle them (defensive check)
                        // This should never be reached for /api/** routes, but just in case
                        if (resourcePath != null && resourcePath.startsWith("api/")) {
                            return null;
                        }
                        
                        // Handle root path - serve index.html
                        if (resourcePath == null || resourcePath.isEmpty()) {
                            Resource indexResource = new ClassPathResource("/static/index.html");
                            if (indexResource.exists()) {
                                return indexResource;
                            }
                            return null;
                        }
                        
                        // Try to serve the requested resource from static folder
                        Resource requestedResource = location.createRelative(resourcePath);
                        
                        // If the requested resource exists, serve it
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }
                        
                        // For client-side routing (SPA), serve index.html for all routes
                        // This allows routes like /trip, /trip?id=19, etc. to work
                        // Even with query parameters, the path part will be handled here
                        Resource indexResource = new ClassPathResource("/static/index.html");
                        if (indexResource.exists()) {
                            return indexResource;
                        }
                        
                        return null;
                    }
                });
    }
}

