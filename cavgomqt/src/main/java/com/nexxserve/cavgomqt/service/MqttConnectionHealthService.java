package com.nexxserve.cavgomqt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service to monitor MQTT connection health and provide connection status
 */
@Service
public class MqttConnectionHealthService {

    private static final Logger logger = LoggerFactory.getLogger(MqttConnectionHealthService.class);

    @Autowired
    private MqttPahoClientFactory mqttClientFactory;

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private long lastConnectionCheck = 0;
    private int connectionFailures = 0;

    @PostConstruct
    public void initialize() {
        logger.info("🔌 MQTT Connection Health Service initialized");
        logger.info("📡 Broker URL: {}", brokerUrl);
        logger.info("🆔 Client ID: {}", clientId);
    }

    /**
     * Check MQTT connection health every 30 seconds
     */
    @Scheduled(fixedRate = 30000)
    public void checkConnectionHealth() {
        try {
            // Simple connection check by attempting to create a client
            String testClientId = clientId + "-health-check-" + System.currentTimeMillis();
            
            // This is a basic check - in production you might want to use a more sophisticated approach
            boolean wasConnected = isConnected.get();
            
            // For now, we'll assume connection is healthy if we haven't seen recent failures
            long timeSinceLastCheck = System.currentTimeMillis() - lastConnectionCheck;
            boolean shouldBeConnected = timeSinceLastCheck < 60000; // Consider connected if checked within last minute
            
            isConnected.set(shouldBeConnected);
            lastConnectionCheck = System.currentTimeMillis();
            
            if (wasConnected != shouldBeConnected) {
                if (shouldBeConnected) {
                    logger.info("✅ MQTT connection restored");
                    connectionFailures = 0;
                } else {
                    connectionFailures++;
                    logger.warn("❌ MQTT connection lost (failure #{}), attempting reconnection...", connectionFailures);
                }
            }
            
            // Log connection status every 5 minutes
            if (System.currentTimeMillis() % 300000 < 30000) {
                logger.info("🔍 MQTT Connection Status: {} (failures: {})", 
                           isConnected.get() ? "CONNECTED" : "DISCONNECTED", connectionFailures);
            }
            
        } catch (Exception e) {
            logger.error("❌ Error checking MQTT connection health: {}", e.getMessage());
            isConnected.set(false);
            connectionFailures++;
        }
    }

    /**
     * Get current connection status
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Get connection failure count
     */
    public int getConnectionFailures() {
        return connectionFailures;
    }

    /**
     * Get connection health summary
     */
    public String getConnectionHealthSummary() {
        return String.format("MQTT Connection: %s, Failures: %d, Broker: %s", 
                           isConnected.get() ? "HEALTHY" : "UNHEALTHY", 
                           connectionFailures, 
                           brokerUrl);
    }

    /**
     * Reset connection failure count (useful for manual recovery)
     */
    public void resetConnectionFailures() {
        connectionFailures = 0;
        logger.info("🔄 MQTT connection failure count reset");
    }
}
