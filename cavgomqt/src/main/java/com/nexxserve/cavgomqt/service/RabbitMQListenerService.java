package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.BookingEventMessage;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import lombok.RequiredArgsConstructor;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import com.nexxserve.cavgomqt.config.RabbitMQConfig;
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


    // Removed RabbitTemplate publish usage; all trip updates are published by NavigaService

    @Autowired
    private NavigaService navigaService;


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
        logger.info("📥 === RECEIVED TRIP EVENT FROM FANOUT (queue bound to: tripservice.trips.updates) ===");
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
            Trip trip = message.getData();
            String event = message.getEvent();

            // Only handle the two canonical Trip Service events:
            // - created: create/register trip in Naviga
            // - cancelled: delete trip in Naviga and cleanup registry
            if ("created".equalsIgnoreCase(event)) {
                handleTripStartedEvent(message, trip);
            } else if ("cancelled".equalsIgnoreCase(event)) {
                handleTripCancelledEvent(message, trip);
            } else {
                logger.info("ℹ️ Ignoring non-core trip event from Trip Service: {}", event);
            }

        } catch (Exception e) {
            logger.error("❌ Error processing trip message from RabbitMQ: {}", e.getMessage(), e);
            logger.error("  - Event: {}", message != null ? message.getEvent() : "null");
            logger.error("  - Trip ID: {}",
                    message != null && message.getData() != null ? message.getData().getId() : "null");
        }
        logger.info("✅ Finished processing trip event from Trip Service fanout");
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

        // NOTE: Trip update events are now published by NavigaService to cavgomqt.trip.updates fanout
        // when Naviga API calls complete. Removed MQTT/RabbitMQ republishing here.
        logger.info("✅ TRIP_STARTED/TRIP_CREATED event processed by NavigaService");
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

        // NOTE: Trip update events are now published by NavigaService to cavgomqt.trip.updates fanout
        logger.info("✅ TRIP_CANCELLED event processed by NavigaService");
    }

    // Removed TRIP_UPDATED handler; only 'created' and 'cancelled' are processed from Trip Service fanout
}