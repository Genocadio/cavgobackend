package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.config.RabbitMQConfig;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import com.nexxserve.cavgomqt.dto.TripWaypoint;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for publishing trip events to RabbitMQ
 * Publishes converted trip events from MQTT to RabbitMQ for further processing
 */
@Service
@RequiredArgsConstructor
public class RabbitMQTripPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQTripPublisherService.class);

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publish trip event message to RabbitMQ trips queue
     * @param tripEventMessage The trip event message to publish
     */
    public void publishTripEvent(TripEventMessage tripEventMessage) {
        try {
            logger.info("📤 Publishing trip event to RabbitMQ: {}", tripEventMessage.getEvent());
            logger.debug("Trip event details: {}", tripEventMessage);

            // Publish to the trips publisher queue (separate from listener queue)
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRIPS_PUBLISHER_QUEUE, tripEventMessage);
            
            // Fanout publishing removed; consolidated publishing handled by NavigaService

            logger.info("✅ Successfully published trip event: {} for trip ID: {}", 
                       tripEventMessage.getEvent(), 
                       tripEventMessage.getData() != null ? tripEventMessage.getData().getId() : "unknown");

        } catch (Exception e) {
            logger.error("❌ Failed to publish trip event to RabbitMQ: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish trip event to RabbitMQ", e);
        }
    }

    /**
     * Publish trip event with additional metadata
     * @param tripEventMessage The trip event message to publish
     * @param sourceTopic The original MQTT topic this event came from
     * @param carId The car ID associated with this trip event
     */
    public void publishTripEventWithMetadata(TripEventMessage tripEventMessage, String sourceTopic, String carId) {
        try {
            logger.info("📤 Publishing trip event to RabbitMQ with metadata:");
            logger.info("  - Event: {}", tripEventMessage.getEvent());
            logger.info("  - Source Topic: {}", sourceTopic);
            logger.info("  - Car ID: {}", carId);
            logger.info("  - Trip ID: {}", tripEventMessage.getData() != null ? tripEventMessage.getData().getId() : "unknown");
            
            // Debug log waypoint info if available (safely)
            if (tripEventMessage.getData() != null 
                && tripEventMessage.getData().getWaypoints() != null 
                && !tripEventMessage.getData().getWaypoints().isEmpty()) {
                List<TripWaypoint> waypoints = tripEventMessage.getData().getWaypoints();
                logger.debug("Trip event waypoints: {} total", waypoints.size());
                if (!waypoints.isEmpty() && waypoints.getFirst() != null) {
                    logger.debug("First waypoint remaining distance: {}", 
                               waypoints.getFirst().getRemainingDistance());
                }
            }

            // Add metadata to the message (you could extend TripEventMessage to include this)
            // For now, we'll just log it and publish the original message
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRIPS_PUBLISHER_QUEUE, tripEventMessage);
            
            // Fanout publishing removed; consolidated publishing handled by NavigaService

            logger.info("✅ Successfully published trip event with metadata to RabbitMQ");

        } catch (Exception e) {
            logger.error("❌ Failed to publish trip event with metadata to RabbitMQ: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to publish trip event with metadata to RabbitMQ", e);
        }
    }

    /**
     * Check if RabbitMQ connection is available
     * @return true if connection is available, false otherwise
     */
    public boolean isConnectionAvailable() {
        try {
            logger.info("🔍 Checking RabbitMQ connection availability...");
            // Try to send a test message to check connection
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRIPS_PUBLISHER_QUEUE, "connection-test");
            logger.info("✅ RabbitMQ connection is available");
            return true;
        } catch (Exception e) {
            logger.warn("⚠️ RabbitMQ connection not available: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
