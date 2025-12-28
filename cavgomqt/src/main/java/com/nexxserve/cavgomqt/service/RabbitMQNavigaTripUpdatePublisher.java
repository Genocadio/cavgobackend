package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.naviga.NavigaTripUpdateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for publishing Naviga trip update events to RabbitMQ
 * 
 * Publishes to fanout exchange: cavgomqt.trip.updates
 * All events are of type "updates" as per requirement.
 * 
 * Events are published whenever:
 * - A trip is successfully created in Naviga
 * - GPS updates are successfully sent to Naviga
 * - A trip is deleted from Naviga
 */
@Service
public class RabbitMQNavigaTripUpdatePublisher {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQNavigaTripUpdatePublisher.class);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String FANOUT_EXCHANGE = "cavgomqt.trip.updates";

    /**
     * Publish a Naviga trip update event to the fanout exchange
     * 
     * @param event The Naviga trip update event to publish
     */
    public void publishTripUpdateEvent(NavigaTripUpdateEvent event) {
        if (event == null) {
            logger.warn("⚠️ Cannot publish null trip update event");
            return;
        }

        try {
            logger.info("📤 Publishing Naviga trip update to fanout exchange:");
            logger.info("  - Exchange: {}", FANOUT_EXCHANGE);
            logger.info("  - Trip ID: {}", event.getTrip() != null ? event.getTrip().getId() : "unknown");
            logger.info("  - Car ID: {}", event.getTrip() != null ? event.getTrip().getCarId() : "unknown");
            logger.info("  - Status: {}", event.getTrip() != null ? event.getTrip().getStatus() : "unknown");
            logger.info("  - Source: {}", event.getSource());
            logger.debug("  - Full Event: {}", event);

            // Publish to fanout exchange with empty routing key (fanout delivers to all queues)
            rabbitTemplate.convertAndSend(FANOUT_EXCHANGE, "", event);

            logger.info("✅ Successfully published Naviga trip update event: tripId={}, status={}",
                    event.getTrip() != null ? event.getTrip().getId() : "unknown",
                    event.getTrip() != null ? event.getTrip().getStatus() : "unknown");

        } catch (Exception e) {
            logger.error("❌ Failed to publish Naviga trip update event: {}", e.getMessage(), e);
            // Don't throw exception - log and continue to avoid disrupting main flow
        }
    }

    /**
     * Publish a simple trip update event
     * 
     * @param tripId The trip ID
     * @param carId The car ID
     * @param status The trip status (CREATED, ACTIVE, COMPLETED, CANCELLED)
     * @param source The source of the update (e.g., "naviga-trip-create", "naviga-gps-batch", "naviga-trip-delete")
     */
    public void publishSimpleUpdate(Long tripId, String carId, String status, String source) {
        try {
            NavigaTripUpdateEvent.NavigaTripDto tripDto = new NavigaTripUpdateEvent.NavigaTripDto(
                    tripId, carId, status);
            NavigaTripUpdateEvent event = new NavigaTripUpdateEvent(tripDto, source);
            publishTripUpdateEvent(event);
        } catch (Exception e) {
            logger.error("❌ Failed to publish simple trip update: {}", e.getMessage(), e);
        }
    }
}
