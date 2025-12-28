package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.naviga.NavigaLocationUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for publishing Naviga location update events to RabbitMQ
 * 
 * Publishes to fanout exchange: cavgomqt.location.updates
 * All events are of type "updates" as per requirement.
 * 
 * Location batches are published BEFORE sending to Naviga API.
 * If RabbitMQ is unavailable, the system continues normally without breaking the flow.
 * 
 * Events are published whenever:
 * - A batch of GPS location updates is received from MQTT and decoded
 */
@Service
public class RabbitMQNavigaLocationUpdatePublisher {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQNavigaLocationUpdatePublisher.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String FANOUT_EXCHANGE = "cavgomqt.location.updates";

    /**
     * Publish a location update event to the fanout exchange
     * If RabbitMQ is unavailable, logs warning but doesn't break the flow
     * 
     * @param event The location update event to publish
     */
    public void publishLocationUpdateEvent(NavigaLocationUpdateEvent event) {
        if (event == null) {
            logger.warn("⚠️ Cannot publish null location update event");
            return;
        }

        try {
            logger.info("📤 Publishing location batch to fanout exchange:");
            logger.info("  - Exchange: {}", FANOUT_EXCHANGE);
            logger.info("  - Car ID: {}", event.getCarId());
            logger.info("  - Locations: {}", event.getLocations() != null ? event.getLocations().size() : 0);
            logger.info("  - Source: {}", event.getSource());
            logger.debug("  - Full Event: {}", event);

            // Publish to fanout exchange with empty routing key (fanout delivers to all queues)
            rabbitTemplate.convertAndSend(FANOUT_EXCHANGE, "", event);

            logger.info("✅ Successfully published location batch event: carId={}, locations={}",
                    event.getCarId(), event.getLocations() != null ? event.getLocations().size() : 0);

        } catch (Exception e) {
            // Don't throw exception - log warning and continue to avoid disrupting main flow
            // Location publication failure should not block GPS sending to Naviga
            logger.warn("⚠️ Failed to publish location update event to RabbitMQ (continuing anyway): {}", 
                    e.getMessage());
            logger.debug("Stack trace: ", e);
        }
    }

    /**
     * Check if RabbitMQ connection is available
     * 
     * @return true if connection appears available, false otherwise
     */
    public boolean isConnectionAvailable() {
        try {
            // Try to verify connection by checking if template is properly initialized
            return rabbitTemplate != null;
        } catch (Exception e) {
            logger.debug("RabbitMQ connection check failed: {}", e.getMessage());
            return false;
        }
    }
}
