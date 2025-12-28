package com.nexxserve.cavgomqt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.stereotype.Component;

/**
 * Logs MQTT subscription and connection events to verify subscriptions are active
 */
@Component
public class MqttSubscriptionLogger {
    private static final Logger logger = LoggerFactory.getLogger(MqttSubscriptionLogger.class);

    @EventListener
    public void onSubscribed(MqttSubscribedEvent event) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("✅ MQTT SUBSCRIPTION CONFIRMED");
        logger.info("  - Topic: {}", event.getMessage());
        logger.info("  - Source: {}", event.getSource());
        logger.info("  - Time: {}", java.time.Instant.ofEpochMilli(event.getTimestamp()));
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    @EventListener
    public void onConnectionFailed(MqttConnectionFailedEvent event) {
        logger.error("═══════════════════════════════════════════════════════════════");
        logger.error("❌ MQTT CONNECTION FAILED");
        logger.error("  - Cause: {}", event.getCause() != null ? event.getCause().getMessage() : "Unknown");
        logger.error("  - Source: {}", event.getSource());
        logger.error("═══════════════════════════════════════════════════════════════");
    }
}
