package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomqt.dto.naviga.NavigationTripResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Service to listen for completed trip events from Navigation API RabbitMQ fanout exchange
 * 
 * Listens to: navigation.trip.update.queue (our local queue)
 * Exchange: navigation.trip.update (fanout, from Navigation API)
 * 
 * When a trip completes in the Navigation API, it publishes to the fanout exchange.
 * This service receives the completed trip and forwards it to MQTT.
 */
@Service
public class RabbitMQNavigationTripListenerService {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQNavigationTripListenerService.class);

    @Autowired
    private MessageChannel tripAssignmentChannel;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitMQNavigaTripUpdatePublisher rabbitMQNavigaTripUpdatePublisher;

    /**
     * Listen for completed trip events from Navigation API
     * 
     * @param navigationResponse The trip response from Navigation API
     */
    @RabbitListener(queues = "navigation.trip.update.queue")
    public void handleNavigationTripCompletion(NavigationTripResponse navigationResponse) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("📥 === RECEIVED COMPLETED TRIP FROM NAVIGATION API (navigation.trip.update fanout) ===");
        logger.info("  - Timestamp: {}", System.currentTimeMillis());

        if (navigationResponse == null || navigationResponse.getTrip() == null) {
            logger.warn("⚠️ Received null navigation response or trip");
            logger.info("═══════════════════════════════════════════════════════════════");
            return;
        }

        try {
            NavigationTripResponse.NavigationTripDto trip = navigationResponse.getTrip();
            
            logger.info("  - Trip ID: {}", trip.getId());
            logger.info("  - Car ID: {}", trip.getCarId());
            logger.info("  - Status: {}", trip.getStatus());
            logger.info("  - Completed At: {}", trip.getCompletedAt());
            logger.info("  - Waypoints Count: {}", trip.getWaypoints() != null ? trip.getWaypoints().size() : 0);
            logger.info("  - Waypoint Progresses: {}", trip.getWaypointProgresses() != null ? trip.getWaypointProgresses().size() : 0);

            // Only process COMPLETED trips
            if (!"COMPLETED".equalsIgnoreCase(trip.getStatus())) {
                logger.info("ℹ️ Ignoring non-completed trip from Navigation API (status={})", trip.getStatus());
                logger.info("═══════════════════════════════════════════════════════════════");
                return;
            }

            // Publish to our own fanout exchange (cavgomqt.trip.updates)
            // This allows other services to consume trip completions from us
            logger.info("📤 Publishing trip completion to cavgomqt.trip.updates fanout exchange");
            rabbitMQNavigaTripUpdatePublisher.publishSimpleUpdate(
                    trip.getId(), 
                    trip.getCarId(), 
                    "COMPLETED", 
                    "navigation-api-fanout"
            );

            // Forward to MQTT for vehicle consumption
            // Topic: car/{carId}/trip/completed
            String topic = String.format("car/%s/trip/completed", trip.getCarId());
            logger.info("📤 Forwarding completed trip to MQTT topic: {}", topic);
            
            try {
                String jsonPayload = objectMapper.writeValueAsString(navigationResponse);
                tripAssignmentChannel.send(
                        MessageBuilder.withPayload(jsonPayload)
                                .setHeader("mqtt_topic", topic)
                                .setHeader("mqtt_qos", 1)
                                .setHeader("mqtt_retained", false)
                                .build()
                );
                logger.info("✅ Successfully published completed trip to MQTT");
            } catch (JsonProcessingException e) {
                logger.error("❌ Failed to serialize navigation response for MQTT: {}", e.getMessage(), e);
            }

            logger.info("✅ Successfully processed and forwarded completed trip from Navigation API");

        } catch (Exception e) {
            logger.error("❌ Error processing completed trip from Navigation API: {}", e.getMessage(), e);
        }

        logger.info("═══════════════════════════════════════════════════════════════");
    }
}
