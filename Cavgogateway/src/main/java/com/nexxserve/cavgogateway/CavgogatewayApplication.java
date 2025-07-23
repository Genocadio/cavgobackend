package com.nexxserve.cavgogateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.event.EventListener;

@SpringBootApplication
@EnableDiscoveryClient
public class CavgogatewayApplication {

    private static final Logger logger = LoggerFactory.getLogger(CavgogatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(CavgogatewayApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("🚀 ================================");
        logger.info("🚀 CAVGO GATEWAY STARTED SUCCESSFULLY");
        logger.info("🚀 Server running on port: 8070");
        logger.info("🚀 CORS enabled for all origins");
        logger.info("🚀 Request/Response logging enabled");
        logger.info("🚀 Management endpoints available at: /actuator");
        logger.info("🚀 ================================");
    }
}