package com.nexxserve.cavgomqt.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service to monitor MQTT connection health via event tracking
 */
@Service
public class MqttConnectionHealthService {

    private static final Logger logger = LoggerFactory.getLogger(MqttConnectionHealthService.class);

    @Value("${mqtt.broker.url}")
    private String brokerUrl;

    @Value("${mqtt.client.id}")
    private String clientId;

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicInteger connectionFailures = new AtomicInteger(0);
    private final AtomicInteger subscriptionCount = new AtomicInteger(0);
    private final AtomicLong lastConnectionTime = new AtomicLong(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private String lastFailureCause = "None";

    @PostConstruct
    public void initialize() {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("🔌 MQTT CONNECTION HEALTH SERVICE INITIALIZED");
        logger.info("  📡 Broker URL: {}", brokerUrl);
        logger.info("  🆔 Client ID: {}", clientId);
        logger.info("  🔗 Protocol: {}", brokerUrl.startsWith("ssl://") ? "SSL/TLS" : "TCP");
        logger.info("  📊 Monitoring: Event-driven (no polling)");
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    /**
     * Track MQTT subscriptions - indicates successful connection
     */
    @EventListener
    public void onSubscribed(MqttSubscribedEvent event) {
        if (!isConnected.get()) {
            isConnected.set(true);
            lastConnectionTime.set(System.currentTimeMillis());
            
            long downtime = lastFailureTime.get() > 0 ? 
                (lastConnectionTime.get() - lastFailureTime.get()) / 1000 : 0;
            
            logger.info("✅ MQTT connection restored");
            logger.info("  - Topic subscribed: {}", event.getMessage());
            if (downtime > 0) {
                logger.info("  - Downtime: {}s", downtime);
            }
            logger.info("  - Total subscriptions: {}", subscriptionCount.incrementAndGet());
        } else {
            subscriptionCount.incrementAndGet();
            logger.debug("📌 Additional subscription: {} (total: {})", 
                event.getMessage(), subscriptionCount.get());
        }
    }

    /**
     * Track connection failures with detailed diagnostics
     */
    @EventListener
    public void onConnectionFailed(MqttConnectionFailedEvent event) {
        isConnected.set(false);
        lastFailureTime.set(System.currentTimeMillis());
        int failures = connectionFailures.incrementAndGet();
        
        Throwable cause = event.getCause();
        lastFailureCause = cause != null ? cause.getMessage() : "Unknown";
        
        logger.error("❌ MQTT connection lost (failure #{})", failures);
        logger.error("  - Time: {}", Instant.now());
        logger.error("  - Cause Type: {}", cause != null ? cause.getClass().getSimpleName() : "Unknown");
        logger.error("  - Cause Message: {}", lastFailureCause);
        logger.error("  - Source: {}", event.getSource());
        
        // Reset subscription count on disconnect
        subscriptionCount.set(0);
    }

    /**
     * Periodic status report every 5 minutes
     */
    @Scheduled(fixedRate = 300000)
    public void logPeriodicStatus() {
        long uptime = isConnected.get() && lastConnectionTime.get() > 0 ?
            (System.currentTimeMillis() - lastConnectionTime.get()) / 1000 : 0;
            
        logger.info("🔍 MQTT Connection Status: {} (failures: {})", 
                   isConnected.get() ? "CONNECTED" : "DISCONNECTED", connectionFailures.get());
        logger.info("  📡 Broker: {} | 🆔 Client: {}", brokerUrl, clientId);
        logger.info("  📊 Active Subscriptions: {}", subscriptionCount.get());
        if (isConnected.get() && uptime > 0) {
            logger.info("  ⏱️ Connected for: {}m {}s", uptime / 60, uptime % 60);
        }
        if (!isConnected.get() && lastFailureTime.get() > 0) {
            logger.warn("  ⚠️ Last failure: {}", lastFailureCause);
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
        return connectionFailures.get();
    }

    /**
     * Get connection health summary
     */
    public String getConnectionHealthSummary() {
        return String.format("MQTT Connection: %s, Failures: %d, Broker: %s", 
                           isConnected.get() ? "HEALTHY" : "UNHEALTHY", 
                           connectionFailures.get(), 
                           brokerUrl);
    }

    /**
     * Reset connection failure count (useful for manual recovery)
     */
    public void resetConnectionFailures() {
        connectionFailures.set(0);
        logger.info("🔄 MQTT connection failure count reset");
    }
}
