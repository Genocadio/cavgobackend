package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.BookingEventMessage;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import com.nexxserve.cavgomqt.dto.TripStatus;
import com.nexxserve.cavgomqt.repository.NavigaTripRepository;
import lombok.RequiredArgsConstructor;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import com.nexxserve.cavgomqt.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RabbitMQListenerService {

    @Autowired
    private MqttService mqttService;

    @Autowired
    private TripNotificationService tripNotificationService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private NavigaService navigaService;

    @Autowired
    private NavigaTripRepository navigaTripRepository;

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQListenerService.class);

    @RabbitListener(queues = "bookings.queue")
    public void handleBookingChanges(BookingEventMessage message) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("📥 === RECEIVED MESSAGE FROM RABBITMQ QUEUE: bookings.queue ===");
        logger.info("  - Timestamp: {}", System.currentTimeMillis());
        logger.info("  - Event Type: {}", message.getEvent());

        if (message.getData() != null && message.getData().getBooking() != null) {
            logger.info("  - Booking ID: {}", message.getData().getBooking().getId());
            logger.info("  - Trip ID: {}", message.getData().getBooking().getTripId());
            logger.info("  - Status: {}", message.getData().getBooking().getStatus());
        } else {
            logger.warn("  ⚠️  Message data or booking is null!");
        }
        logger.info("═══════════════════════════════════════════════════════════════");

        try {
            // BookingEventMessage eventMessage = objectMapper.readValue(message,
            // BookingEventMessage.class);
            // BookingResponse bookingResponse = message.getData();
            mqttService.publishBooking(message);

            logger.info("Event: {}", message.getEvent());
            //
            // logger.info("Processed booking change:");
            // logger.info("- Booking ID: {}", bookingResponse.getBooking().getId());
            // logger.info("- Trip ID: {}", bookingResponse.getBooking().getTripId());
            // logger.info("- User: {} ({})", bookingResponse.getBooking().getUserName(),
            // bookingResponse.getBooking().getUserPhone());
            // logger.info("- Status: {}", bookingResponse.getBooking().getStatus());
            // logger.info("- Tickets: {}",
            // bookingResponse.getBooking().getNumberOfTickets());
            // logger.info("- Total Amount: {}",
            // bookingResponse.getBooking().getTotalAmount());
            // logger.info("- Message: {}", bookingResponse.getMessage());
            // logger.info("- Payment Reference: {}",
            // bookingResponse.getPaymentReference());
            //
            // if (bookingResponse.getBooking().getPayment() != null) {
            // logger.info("- Payment Status: {}",
            // bookingResponse.getBooking().getPayment().getStatus());
            // logger.info("- Payment Method: {}",
            // bookingResponse.getBooking().getPayment().getPaymentMethod());
            // }

        } catch (Exception e) {
            logger.error("❌ Error processing booking message from RabbitMQ: {}", e.getMessage(), e);
            logger.error("  - Event: {}", message != null ? message.getEvent() : "null");
        }
        logger.info("✅ Finished processing message from bookings.queue");
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    @RabbitListener(queues = "tripservicesend")
    public void handleTripChanges(TripEventMessage message) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("📥 === RECEIVED MESSAGE FROM RABBITMQ QUEUE: tripservicesend ===");
        logger.info("  - Timestamp: {}", System.currentTimeMillis());
        logger.info("  - Event Type: {}", message.getEvent());

        if (message.getData() != null) {
            Trip trip = message.getData();
            logger.info("  - Trip ID: {}", trip.getId());
            logger.info("  - Vehicle ID: {}", trip.getVehicleId());
            logger.info("  - Status: {}", trip.getStatus());
            if (trip.getVehicle() != null) {
                logger.info("  - Vehicle License Plate: {}", trip.getVehicle().getLicensePlate());
            }
        } else {
            logger.warn("  ⚠️  Message data is null!");
        }
        logger.info("═══════════════════════════════════════════════════════════════");

        try {
            // Also publish to fanout exchange for multiple services to consume
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRIPS_FANOUT_EXCHANGE, "", message);
            logger.info("📤 Published to fanout exchange: {}", RabbitMQConfig.TRIPS_FANOUT_EXCHANGE);

            Trip trip = message.getData();
            String event = message.getEvent();

            // Handle different trip event types
            // Also check trip status in data, not just event type
            if (trip.getStatus() == TripStatus.COMPLETED) {
                // If trip is completed, always send completion notification regardless of event
                // type
                handleTripCompletedEvent(message, trip);
            } else {
                switch (event) {
                    case "TRIP_CREATED":
                    case "trip_created":
                    case "CREATED":
                    case "created":
                    case "TRIP_STARTED":
                    case "trip_started":
                        handleTripStartedEvent(message, trip);
                        break;
                    case "TRIP_COMPLETED":
                    case "trip_completed":
                        handleTripCompletedEvent(message, trip);
                        break;
                    case "TRIP_CANCELLED":
                    case "trip_cancelled":
                        handleTripCancelledEvent(message, trip);
                        break;
                    case "TRIP_UPDATED":
                    case "trip_updated":
                    case "TRIP_PROGRESS_UPDATE":
                    case "trip_progress_update":
                    case "progress_update":
                        handleTripUpdatedEvent(message, trip);
                        break;
                    default:
                        logger.info("📋 Unhandled trip event type: '{}', treating as TRIP_UPDATED", event);
                        logger.info("  - This includes events like: created, updated, etc.");
                        // For unhandled events, still check trip status and send notifications
                        handleTripUpdatedEvent(message, trip);
                        break;
                }
            }

        } catch (Exception e) {
            logger.error("❌ Error processing trip message from RabbitMQ: {}", e.getMessage(), e);
            logger.error("  - Event: {}", message != null ? message.getEvent() : "null");
            logger.error("  - Trip ID: {}",
                    message != null && message.getData() != null ? message.getData().getId() : "null");
        }
        logger.info("✅ Finished processing message from trips.queue");
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    @RabbitListener(queues = RabbitMQConfig.BOOKINGS_BUNDLE_REPLY_QUEUE)
    public void handleBookingBundleReplies(BookingBundle bundle) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("📥 === RECEIVED MESSAGE FROM RABBITMQ QUEUE: {} ===", RabbitMQConfig.BOOKINGS_BUNDLE_REPLY_QUEUE);
        logger.info("  - Timestamp: {}", System.currentTimeMillis());

        try {
            // Validate input
            if (bundle == null) {
                logger.error("❌ Booking bundle reply is null - cannot process");
                return;
            }

            logger.info("  - Trip ID: {}", bundle.tripId != null ? bundle.tripId : "null");
            logger.info("  - Booking ID: {}", bundle.booking != null ? bundle.booking.id : "null");
            logger.info("  - Payment ID: {}", bundle.payment != null ? bundle.payment.id : "null");
            logger.info("  - Ticket ID: {}", bundle.tickets != null ? bundle.tickets.size() : "null");

            // Forward to MQTT
            logger.info("📤 Forwarding booking bundle reply to MQTT...");
            mqttService.publishBookingBundle(bundle);

            logger.info("✅ SUCCESS: Booking bundle reply forwarded to MQTT");
            logger.info("  - Trip ID: {}", bundle.tripId);
            logger.info("  - Booking ID: {}", bundle.booking != null ? bundle.booking.id : "null");

        } catch (Exception e) {
            logger.error("❌ FAILED to forward booking bundle reply to MQTT:");
            logger.error("  - Queue: {}", RabbitMQConfig.BOOKINGS_BUNDLE_REPLY_QUEUE);
            logger.error("  - Trip ID: {}", bundle != null ? bundle.tripId : "null");
            logger.error("  - Booking ID: {}", bundle != null && bundle.booking != null ? bundle.booking.id : "null");
            logger.error("  - Error: {}", e.getMessage());
            logger.error("  - Exception type: {}", e.getClass().getSimpleName());
            e.printStackTrace();
        }
        logger.info("✅ Finished processing message from {}", RabbitMQConfig.BOOKINGS_BUNDLE_REPLY_QUEUE);
        logger.info("═══════════════════════════════════════════════════════════════");
    }

    private void handleTripStartedEvent(TripEventMessage message, Trip trip) {
        logger.info("🚗 Processing TRIP_STARTED/TRIP_CREATED event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})",
                trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Status: {}", trip.getStatus());
        logger.info("  - Departure Time: {}", trip.getDepartureTime());

        // Send trip creation to Naviga API
        try {
            navigaService.createTrip(trip);
        } catch (Exception e) {
            logger.error("❌ Failed to create trip in Naviga API: {}", e.getMessage(), e);
            // Don't fail the main flow - continue processing
        }

        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_STARTED/TRIP_CREATED event processed and forwarded to MQTT");
    }

    private void handleTripCompletedEvent(TripEventMessage message, Trip trip) {
        logger.info("✅ Processing TRIP_COMPLETED event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})",
                trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Completion Time: {}", trip.getCompletionTime());

        // Remove trip from Naviga database registry
        if (trip.getId() != null) {
            try {
                navigaTripRepository.deleteByTripId(Long.valueOf(trip.getId()));
                logger.info("🗑️ Removed completed trip from Naviga registry: tripId={}", trip.getId());
            } catch (Exception e) {
                logger.error("❌ Failed to remove trip from Naviga registry: {}", e.getMessage());
            }
        }

        // Send completion notification
        tripNotificationService.sendCompletionNotification(trip);

        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_COMPLETED event processed and forwarded to MQTT");
    }

    private void handleTripCancelledEvent(TripEventMessage message, Trip trip) {
        logger.info("❌ Processing TRIP_CANCELLED event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})",
                trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Status: {}", trip.getStatus());

        // Delete trip from Naviga API
        if (trip.getId() != null) {
            try {
                navigaService.deleteTrip(Long.valueOf(trip.getId()));
            } catch (Exception e) {
                logger.error("❌ Failed to delete trip from Naviga API: {}", e.getMessage());
                // Don't fail the main flow - continue processing
            }
        }

        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_CANCELLED event processed and forwarded to MQTT");
    }

    private void handleTripUpdatedEvent(TripEventMessage message, Trip trip) {
        logger.info("🔄 Processing TRIP_UPDATED/TRIP_PROGRESS_UPDATE event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})",
                trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Status: {}", trip.getStatus());

        // Log location and progress information
        if (trip.getCurrentLatitude() != null && trip.getCurrentLongitude() != null) {
            logger.info("  - Current Location: {}, {}", trip.getCurrentLatitude(), trip.getCurrentLongitude());
        }

        if (trip.getCurrentSpeed() != null) {
            logger.info("  - Current Speed: {} km/h", trip.getCurrentSpeed());
        }

        if (trip.getRemainingTimeToDestination() != null) {
            logger.info("  - Remaining Time: {} seconds", trip.getRemainingTimeToDestination());
        }

        if (trip.getRemainingDistanceToDestination() != null) {
            logger.info("  - Remaining Distance: {} meters", trip.getRemainingDistanceToDestination());
        }

        // Log waypoint information
        if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
            logger.info("  - Waypoints: {} total", trip.getWaypoints().size());
            trip.getWaypoints().forEach(waypoint -> {
                if (waypoint.getLocation() != null) {
                    logger.info("    - Waypoint {}: {} (passed: {}, next: {})",
                            waypoint.getOrder(),
                            waypoint.getLocation().getCustomName() != null ? waypoint.getLocation().getCustomName()
                                    : waypoint.getLocation().getGooglePlaceName(),
                            waypoint.getIsPassed(),
                            waypoint.getIsNext());
                }
            });
        }

        // Check trip status - if completed, send completion notification
        if (trip.getStatus() == TripStatus.COMPLETED) {
            logger.info("📢 Trip status is COMPLETED, sending completion notification");
            handleTripCompletedEvent(message, trip);
            return;
        }

        // Check and send "about to complete" notification if conditions are met
        tripNotificationService.checkAndSendAboutToCompleteNotification(trip);

        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_UPDATED/TRIP_PROGRESS_UPDATE event processed and forwarded to MQTT");
    }
}