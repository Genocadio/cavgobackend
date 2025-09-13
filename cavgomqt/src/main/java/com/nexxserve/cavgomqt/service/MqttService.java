package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.BookingEventMessage;
import com.nexxserve.cavgomqt.dto.TripEventMessage;
import com.nexxserve.cavgomqt.dto.mqtt.BookingBundle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MqttService {

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    @Autowired
    private MessageChannel tripAssignmentChannel;

    @Autowired
    private MessageChannel bookingUpdatesChannel;

    @Autowired
    private MessageChannel heartbeatOutboundChannel;

    @Autowired
    private MessageChannel bookingBundleOutboundChannel;

    private final ObjectMapper objectMapper = new ObjectMapper();


    /**
     * Simple trip addition for testing - sends basic trip data to specified vehicle
     */
    public void addTrip(Long vehicleId, String tripId, String startLocation, String endLocation) {
        try {
            // Validate vehicle exists
            if (vehicleRegistryService.getVehicleByBackendId(vehicleId) == null) {
                System.err.println("❌ Vehicle " + vehicleId + " does not exist in registry");
                return;
            }
            vehicleRegistryService.setActiveTrip(vehicleId, tripId);
            String topic = "car/" + vehicleId + "/trip";

            Map<String, Object> payload = new HashMap<>();
            payload.put("trip_id", tripId);
            payload.put("vehicle_id", vehicleId);
            payload.put("start_location", startLocation);
            payload.put("end_location", endLocation);
            payload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(payload);

            tripAssignmentChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false)
                            .build()
            );

            System.out.println("✅ Simple trip " + tripId + " added for vehicle " + vehicleId);

        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize simple trip: " + e.getMessage());
        }
    }

    public  void publishBooking(BookingEventMessage message) {
        try {
            String tripId = message.getData().getBooking().getTripId().toString();
            String topic = "trip/" + tripId + "/booking";
            String jsonPayload = objectMapper.writeValueAsString(message);
            bookingUpdatesChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false) // Don't retain booking events
                            .build()
            );
            System.out.println("📦 Booking event published for trip " + tripId);
        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize booking event: " + e.getMessage());
        }
    }

    public void publishTrip(TripEventMessage message) {
        try {
            String carId = message.getData().getVehicleId().toString();
            String topic = "car/" + carId+ "/trip";
            String jsonPayload = objectMapper.writeValueAsString(message);
            tripAssignmentChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false) // Don't retain trip events
                            .build()
            );
            System.out.println("🚗 Trip event published for trip " + carId);
        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize trip event: " + e.getMessage());
        }
    }

    /**
     * Send booking update to all cars running a specific trip
     */
    public void sendBookingUpdate(String tripId, BookingUpdate booking) {
        try {
            String topic = "trip/" + tripId + "/bookings";

            Map<String, Object> payload = new HashMap<>();
            payload.put("trip_id", tripId);
            payload.put("booking", booking);
            payload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(payload);

            bookingUpdatesChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false) // Don't retain booking updates
                            .build()
            );

            System.out.println("📋 Booking update sent for trip " + tripId + ": " + booking.getAction());

        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize booking update: " + e.getMessage());
        }
    }

    /**
     * Convenience method for new bookings
     */
    public void addBooking(String tripId, String bookingId, String passengerId,
                           String pickupLocation, String dropoffLocation) {
        BookingUpdate booking = new BookingUpdate();
        booking.setBookingId(bookingId);
        booking.setPassengerId(passengerId);
        booking.setPickupLocation(pickupLocation);
        booking.setDropoffLocation(dropoffLocation);
        booking.setAction("NEW");

        sendBookingUpdate(tripId, booking);
    }


    /**
     * Send ping to a specific car
     */
    public void pingCar(String carId) {
        try {
            String topic = "car/" + carId + "/ping";

            Map<String, Object> payload = new HashMap<>();
            payload.put("car_id", carId);
            payload.put("ping_time", System.currentTimeMillis());
            payload.put("expected_response", "pong");

            String jsonPayload = objectMapper.writeValueAsString(payload);

            heartbeatOutboundChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false)
                            .build()
            );

            System.out.println("🏓 Ping sent to car " + carId);

        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize ping: " + e.getMessage());
        }
    }

    /**
     * Broadcast ping to all cars
     */
    public void pingAllCars() {
        // Note: This uses wildcard topic, cars should subscribe to car/{their_id}/ping
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("broadcast", true);
            payload.put("ping_time", System.currentTimeMillis());
            payload.put("expected_response", "pong");

            String jsonPayload = objectMapper.writeValueAsString(payload);

            // You might want to maintain a list of active car IDs and ping them individually
            // This is just an example of how you could structure a broadcast
            System.out.println("🏓 Broadcast ping initiated");

        } catch (JsonProcessingException e) {
            System.err.println("❌ Failed to serialize broadcast ping: " + e.getMessage());
        }
    }

    public void publishBookingBundle(BookingBundle bundle) {
        System.out.println("📤 === PUBLISHING BOOKING BUNDLE TO MQTT ===");
        System.out.println("  - Timestamp: " + System.currentTimeMillis());
        
        try {
            // Validate input
            if (bundle == null) {
                System.err.println("❌ Booking bundle is null - cannot publish to MQTT");
                return;
            }
            
            String tripId = bundle.tripId;
            if (tripId == null || tripId.isEmpty()) {
                System.err.println("❌ Missing trip_id in booking bundle; cannot publish to MQTT");
                System.err.println("  - Bundle details: " + bundle);
                return;
            }
            
            System.out.println("  - Trip ID: " + tripId);
            System.out.println("  - Booking ID: " + (bundle.booking != null ? bundle.booking.id : "null"));
            System.out.println("  - Payment ID: " + (bundle.payment != null ? bundle.payment.id : "null"));
            System.out.println("  - Ticket ID: " + (bundle.ticket != null ? bundle.ticket.id : "null"));
            
            String topic = "trip/" + tripId + "/booking_bundle";
            System.out.println("  - MQTT Topic: " + topic);
            
            // Serialize to JSON
            String jsonPayload = objectMapper.writeValueAsString(bundle);
            System.out.println("  - Payload length: " + jsonPayload.length());
            System.out.println("  - Payload preview: " + (jsonPayload.length() > 200 ? jsonPayload.substring(0, 200) + "..." : jsonPayload));
            
            // Send to MQTT
            System.out.println("📤 Sending booking bundle to MQTT...");
            bookingBundleOutboundChannel.send(
                    MessageBuilder.withPayload(jsonPayload)
                            .setHeader("mqtt_topic", topic)
                            .setHeader("mqtt_qos", 1)
                            .setHeader("mqtt_retained", false)
                            .build()
            );
            
            System.out.println("✅ SUCCESS: Booking bundle published to MQTT");
            System.out.println("  - Topic: " + topic);
            System.out.println("  - Trip ID: " + tripId);
            System.out.println("  - Booking ID: " + (bundle.booking != null ? bundle.booking.id : "null"));
            
        } catch (JsonProcessingException e) {
            System.err.println("❌ FAILED to serialize booking bundle for MQTT:");
            System.err.println("  - Error: " + e.getMessage());
            System.err.println("  - Trip ID: " + (bundle != null ? bundle.tripId : "null"));
            System.err.println("  - Booking ID: " + (bundle != null && bundle.booking != null ? bundle.booking.id : "null"));
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ FAILED to publish booking bundle to MQTT:");
            System.err.println("  - Error: " + e.getMessage());
            System.err.println("  - Trip ID: " + (bundle != null ? bundle.tripId : "null"));
            System.err.println("  - Exception type: " + e.getClass().getSimpleName());
            e.printStackTrace();
        }
    }


    public static class BookingUpdate {
        private String bookingId;
        private String passengerId;
        private String pickupLocation;
        private String dropoffLocation;
        private String action; // NEW, CANCELLED, UPDATED
        private long timestamp;

        // Getters and setters
        public String getBookingId() { return bookingId; }
        public void setBookingId(String bookingId) { this.bookingId = bookingId; }

        public String getPassengerId() { return passengerId; }
        public void setPassengerId(String passengerId) { this.passengerId = passengerId; }

        public String getPickupLocation() { return pickupLocation; }
        public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }

        public String getDropoffLocation() { return dropoffLocation; }
        public void setDropoffLocation(String dropoffLocation) { this.dropoffLocation = dropoffLocation; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}