package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.BookingEventMessage;
import com.nexxserve.cavgomqt.dto.BookingResponse;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RabbitMQListenerService {



    @Autowired
    private MqttService mqttService;
    private static final Logger logger = LoggerFactory.getLogger(RabbitMQListenerService.class);

    @RabbitListener(queues = "bookings.queue")
    public void handleBookingChanges(BookingEventMessage message) {
        logger.info("Received booking message: {}", message.toString());

        try {
//            BookingEventMessage eventMessage = objectMapper.readValue(message, BookingEventMessage.class);
//            BookingResponse bookingResponse = message.getData();
            mqttService.publishBooking(message);


            logger.info("Event: {}", message.getEvent());
//
//            logger.info("Processed booking change:");
//            logger.info("- Booking ID: {}", bookingResponse.getBooking().getId());
//            logger.info("- Trip ID: {}", bookingResponse.getBooking().getTripId());
//            logger.info("- User: {} ({})", bookingResponse.getBooking().getUserName(), bookingResponse.getBooking().getUserPhone());
//            logger.info("- Status: {}", bookingResponse.getBooking().getStatus());
//            logger.info("- Tickets: {}", bookingResponse.getBooking().getNumberOfTickets());
//            logger.info("- Total Amount: {}", bookingResponse.getBooking().getTotalAmount());
//            logger.info("- Message: {}", bookingResponse.getMessage());
//            logger.info("- Payment Reference: {}", bookingResponse.getPaymentReference());
//
//            if (bookingResponse.getBooking().getPayment() != null) {
//                logger.info("- Payment Status: {}", bookingResponse.getBooking().getPayment().getStatus());
//                logger.info("- Payment Method: {}", bookingResponse.getBooking().getPayment().getPaymentMethod());
//            }

        } catch (Exception e) {
            logger.error("Error parsing booking message: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = "trips.queue")
    public void handleTripChanges(TripEventMessage message) {
        logger.info("📥 Received trip message from RabbitMQ: {}", message.getEvent());

        try {
            Trip trip = message.getData();
            String event = message.getEvent();
            
            // Handle different trip event types
            switch (event) {
                case "TRIP_STARTED":
                    handleTripStartedEvent(message, trip);
                    break;
                case "TRIP_COMPLETED":
                    handleTripCompletedEvent(message, trip);
                    break;
                case "TRIP_CANCELLED":
                    handleTripCancelledEvent(message, trip);
                    break;
                case "TRIP_UPDATED":
                case "TRIP_PROGRESS_UPDATE":
                    handleTripUpdatedEvent(message, trip);
                    break;
                default:
                    logger.info("📋 Unhandled trip event type: {}, forwarding to MQTT", event);
                    mqttService.publishTrip(message);
                    break;
            }

        } catch (Exception e) {
            logger.error("❌ Error processing trip message from RabbitMQ: {}", e.getMessage(), e);
        }
    }

    private void handleTripStartedEvent(TripEventMessage message, Trip trip) {
        logger.info("🚗 Processing TRIP_STARTED event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})", 
                   trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                   trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Status: {}", trip.getStatus());
        logger.info("  - Departure Time: {}", trip.getDepartureTime());
        
        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_STARTED event processed and forwarded to MQTT");
    }

    private void handleTripCompletedEvent(TripEventMessage message, Trip trip) {
        logger.info("✅ Processing TRIP_COMPLETED event from RabbitMQ:");
        logger.info("  - Trip ID: {}", trip.getId());
        logger.info("  - Vehicle: {} ({})", 
                   trip.getVehicle() != null ? trip.getVehicle().getLicensePlate() : "unknown",
                   trip.getVehicle() != null ? trip.getVehicle().getCompanyName() : "unknown");
        logger.info("  - Completion Time: {}", trip.getCompletionTime());
        
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
                            waypoint.getLocation().getCustomName() != null ?
                                    waypoint.getLocation().getCustomName() : waypoint.getLocation().getGooglePlaceName(),
                            waypoint.getIsPassed(),
                            waypoint.getIsNext());
                }
            });
        }
        
        // Forward to MQTT for other services
        mqttService.publishTrip(message);
        logger.info("✅ TRIP_UPDATED/TRIP_PROGRESS_UPDATE event processed and forwarded to MQTT");
    }
}