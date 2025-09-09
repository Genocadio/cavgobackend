package com.nexxserve.cavgomqt.controller;

import com.nexxserve.cavgomqt.dto.SimpleTripRequest;
import com.nexxserve.cavgomqt.service.MqttService;
import com.nexxserve.cavgomqt.service.TripReceiverService;
import com.nexxserve.cavgomqt.service.MqttConnectionHealthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mqtt")
public class MqttController {

    @Autowired
    private MqttService mqttService;

    @Autowired
    private TripReceiverService tripReceiverService;

    @Autowired
    private MqttConnectionHealthService mqttConnectionHealthService;

    // === TRIP MANAGEMENT ENDPOINTS ===

    /**
     * Simple trip addition for testing - minimal data
     */
    @PostMapping("/trips/add")
    public ResponseEntity<String> addSimpleTrip(@RequestBody SimpleTripRequest request) {
        mqttService.addTrip(
                request.getVehicleId(),
                request.getTripId(),
                request.getStartLocation(),
                request.getEndLocation()
        );

        return ResponseEntity.ok("Simple trip " + request.getTripId() +
                " added for vehicle " + request.getVehicleId());
    }

    @PostMapping("/trips/{tripId}/bookings")
    public ResponseEntity<String> addBooking(
            @PathVariable String tripId,
            @RequestBody BookingRequest request) {

        mqttService.addBooking(
                tripId,
                request.getBookingId(),
                request.getPassengerId(),
                request.getPickupLocation(),
                request.getDropoffLocation()
        );

        return ResponseEntity.ok("Booking added to trip " + tripId);
    }


    @PostMapping("/trips/{tripId}/bookings/{bookingId}/update")
    public ResponseEntity<String> updateBooking(
            @PathVariable String tripId,
            @PathVariable String bookingId,
            @RequestBody BookingUpdateRequest request) {

        MqttService.BookingUpdate update = new MqttService.BookingUpdate();
        update.setBookingId(bookingId);
        update.setPassengerId(request.getPassengerId());
        update.setPickupLocation(request.getPickupLocation());
        update.setDropoffLocation(request.getDropoffLocation());
        update.setAction("UPDATED");
        update.setTimestamp(System.currentTimeMillis());

        mqttService.sendBookingUpdate(tripId, update);

        return ResponseEntity.ok("Booking " + bookingId + " updated for trip " + tripId);
    }

    // === HEARTBEAT MANAGEMENT ENDPOINTS ===

    @PostMapping("/cars/{carId}/ping")
    public ResponseEntity<String> pingCar(@PathVariable String carId) {
        mqttService.pingCar(carId);
        return ResponseEntity.ok("Ping sent to car " + carId);
    }

    @PostMapping("/cars/ping-all")
    public ResponseEntity<String> pingAllCars() {
        mqttService.pingAllCars();
        return ResponseEntity.ok("Broadcast ping sent to all cars");
    }

    // === TRIP RECEIVER TEST ENDPOINTS ===

    /**
     * Test endpoint to simulate receiving a trip event message
     * This can be used to test the TripReceiverService without MQTT
     */
    @PostMapping("/test/trip-event")
    public ResponseEntity<String> testTripEvent(
            @RequestParam String topic,
            @RequestBody String payload) {
        
        try {
            tripReceiverService.processTripEventMessage(topic, payload);
            return ResponseEntity.ok("Trip event processed successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body("Error processing trip event: " + e.getMessage());
        }
    }

    // === CONNECTION HEALTH ENDPOINTS ===

    /**
     * Get MQTT connection health status
     */
    @GetMapping("/health")
    public ResponseEntity<Object> getConnectionHealth() {
        boolean isConnected = mqttConnectionHealthService.isConnected();
        int failures = mqttConnectionHealthService.getConnectionFailures();
        String healthSummary = mqttConnectionHealthService.getConnectionHealthSummary();
        
        return ResponseEntity.ok()
                .body(new Object() {
                    public final boolean connected = isConnected;
                    public final int connectionFailures = failures;
                    public final String summary = healthSummary;
                    public final long timestamp = System.currentTimeMillis();
                });
    }

    /**
     * Reset MQTT connection failure count
     */
    @PostMapping("/health/reset")
    public ResponseEntity<String> resetConnectionFailures() {
        mqttConnectionHealthService.resetConnectionFailures();
        return ResponseEntity.ok("Connection failure count reset");
    }

    public static class BookingRequest {
        private String bookingId;
        private String passengerId;
        private String pickupLocation;
        private String dropoffLocation;

        // Getters and setters
        public String getBookingId() { return bookingId; }
        public void setBookingId(String bookingId) { this.bookingId = bookingId; }

        public String getPassengerId() { return passengerId; }
        public void setPassengerId(String passengerId) { this.passengerId = passengerId; }

        public String getPickupLocation() { return pickupLocation; }
        public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }

        public String getDropoffLocation() { return dropoffLocation; }
        public void setDropoffLocation(String dropoffLocation) { this.dropoffLocation = dropoffLocation; }
    }

    public static class BookingUpdateRequest {
        private String passengerId;
        private String pickupLocation;
        private String dropoffLocation;

        // Getters and setters
        public String getPassengerId() { return passengerId; }
        public void setPassengerId(String passengerId) { this.passengerId = passengerId; }

        public String getPickupLocation() { return pickupLocation; }
        public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }

        public String getDropoffLocation() { return dropoffLocation; }
        public void setDropoffLocation(String dropoffLocation) { this.dropoffLocation = dropoffLocation; }
    }
}