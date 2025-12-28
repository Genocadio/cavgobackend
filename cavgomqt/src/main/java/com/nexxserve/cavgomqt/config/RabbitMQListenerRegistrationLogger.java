package com.nexxserve.cavgomqt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Component to log all RabbitMQ listener subscriptions on application startup.
 * This helps verify that the service is properly configured and listening to the correct queues.
 */
@Component
public class RabbitMQListenerRegistrationLogger {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQListenerRegistrationLogger.class);

    @Autowired
    private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void logRabbitMQSubscriptions() {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("🐰 RABBITMQ LISTENER SUBSCRIPTIONS");
        logger.info("═══════════════════════════════════════════════════════════════");
        
        Set<String> listenerIds = rabbitListenerEndpointRegistry.getListenerContainerIds();
        
        if (listenerIds == null || listenerIds.isEmpty()) {
            logger.warn("⚠️  No RabbitMQ listeners found! Check @RabbitListener annotations.");
        } else {
            logger.info("✅ Found {} active RabbitMQ listener(s):", listenerIds.size());
            logger.info("");
            
            for (String listenerId : listenerIds) {
                var container = rabbitListenerEndpointRegistry.getListenerContainer(listenerId);
                if (container != null) {
                    String queueNames = "See @RabbitListener annotation";
                    if (container instanceof org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer) {
                        var abstractContainer = (org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer) container;
                        String[] queues = abstractContainer.getQueueNames();
                        if (queues != null && queues.length > 0) {
                            queueNames = String.join(", ", queues);
                        }
                    }
                    
                    boolean isRunning = container.isRunning();
                    
                    logger.info("  📋 Listener ID: {}", listenerId);
                    logger.info("     └─ Queue(s): {}", queueNames);
                    logger.info("     └─ Status: {}", isRunning ? "✅ RUNNING" : "❌ STOPPED");
                    logger.info("");
                }
            }
        }
        
        // Also log expected queues
        logger.info("📌 Expected Queues Being Listened:");
        logger.info("   1. {} - Trip events from backend services", RabbitMQConfig.TRIPS_QUEUE);
        logger.info("      └─ Bound to exchange: {} (fanout)", RabbitMQConfig.TRIP_SERVICE_TRIPS_UPDATES_EXCHANGE);
        logger.info("   2. {} - Booking events from backend services", RabbitMQConfig.BOOKINGS_QUEUE);
        logger.info("   3. {} - Booking bundle replies", RabbitMQConfig.BOOKINGS_BUNDLE_REPLY_QUEUE);
        logger.info("   4. {} - Vehicle settings updates", RabbitMQConfig.VEHICLE_SETTINGS_QUEUE);
        logger.info("   5. {} - Completed trip events from Navigation API", RabbitMQConfig.NAVIGATION_TRIP_UPDATE_QUEUE);
        logger.info("      └─ Bound to exchange: {} (fanout)", RabbitMQConfig.NAVIGATION_TRIP_UPDATE_EXCHANGE);
        logger.info("");
        logger.info("═══════════════════════════════════════════════════════════════");
    }
}

