package com.nexxserve.cavgomqt.controller;

import com.nexxserve.cavgomqt.dto.*;
import com.nexxserve.cavgomqt.service.MqttService;
import com.nexxserve.cavgomqt.service.TripReceiverService;
import com.nexxserve.cavgomqt.service.MqttConnectionHealthService;
import com.nexxserve.cavgomqt.service.TripNotificationService;
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

    @Autowired
    private TripNotificationService tripNotificationService;

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

    // === TRIP NOTIFICATION TEST ENDPOINTS ===

    /**
     * Test endpoint to send both trip notifications with mock data.
     * This endpoint:
     * 1. Creates a mock trip with IN_PROGRESS status and < 1km remaining distance
     * 2. Sends "about to complete" notification
     * 3. Creates a mock trip with COMPLETED status
     * 4. Sends completion notification (which also deletes the "about to complete" record)
     * 
     * @param companyId Optional company ID. If provided, notifications will be sent to "tripsupdates_{companyId}" topic.
     *                  If not provided, notifications will be sent to "tripsupdates" topic.
     * 
     * Use this to test Firebase notifications without waiting for real trips.
     */
    @PostMapping("/test/trip-notifications")
    public ResponseEntity<Object> testTripNotifications(
            @RequestParam(required = false) Integer companyId) {
        try {
            StringBuilder result = new StringBuilder();
            result.append("🧪 Testing Trip Notifications\n\n");

            // Create mock trip ID
            Integer mockTripId = 99999;
            Integer mockVehicleId = 12345;
            String mockLicensePlate = "TEST-123";
            // Use provided companyId or null (null will use global topic)
            Integer mockCompanyId = companyId;

            // Determine topic name
            String topicName = mockCompanyId != null ? "tripsupdates_" + mockCompanyId : "tripsupdates";
            result.append("📡 Topic: ").append(topicName).append("\n");
            if (companyId != null) {
                result.append("🏢 Company ID: ").append(companyId).append("\n");
            } else {
                result.append("🌐 Using global topic (no company_id provided)\n");
            }
            result.append("\n");

            // === Test 1: "About to Complete" Notification ===
            result.append("1️⃣ Testing 'About to Complete' Notification:\n");
            Trip aboutToCompleteTrip = createMockTrip(
                mockTripId,
                mockVehicleId,
                mockLicensePlate,
                mockCompanyId,
                TripStatus.IN_PROGRESS,
                500L // 500 meters remaining (< 1km)
            );

            tripNotificationService.checkAndSendAboutToCompleteNotification(aboutToCompleteTrip);
            result.append("   ✅ Sent 'about to complete' notification\n");
            result.append("   - Trip ID: ").append(mockTripId).append("\n");
            result.append("   - Remaining Distance: 500m (< 1km)\n");
            result.append("   - Status: IN_PROGRESS\n");
            result.append("   - Record saved to database\n\n");

            // === Test 2: Completion Notification ===
            result.append("2️⃣ Testing 'Completion' Notification:\n");
            Trip completedTrip = createMockTrip(
                mockTripId,
                mockVehicleId,
                mockLicensePlate,
                mockCompanyId,
                TripStatus.COMPLETED,
                0L // 0 meters remaining
            );

            tripNotificationService.sendCompletionNotification(completedTrip);
            result.append("   ✅ Sent completion notification\n");
            result.append("   - Trip ID: ").append(mockTripId).append("\n");
            result.append("   - Status: COMPLETED\n");
            result.append("   - 'About to complete' record deleted from database\n\n");

            result.append("✅ All notifications sent successfully!\n");
            result.append("📱 Check your Firebase topic '").append(topicName).append("' to verify notifications were received.\n");

            return ResponseEntity.ok()
                    .body(new Object() {
                        public final String message = result.toString();
                        public final boolean success = true;
                        public final int tripId = mockTripId;
                        public final String topic = topicName;
                        public final Integer companyId = mockCompanyId;
                    });

        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(new Object() {
                        public final String error = "Failed to send test notifications: " + e.getMessage();
                        public final boolean success = false;
                    });
        }
    }

    /**
     * Create a mock trip with all required fields for testing
     */
    private Trip createMockTrip(Integer tripId, Integer vehicleId, String licensePlate, 
                               Integer companyId, TripStatus status, Long remainingDistance) {
        Trip trip = new Trip();
        trip.setId(tripId);
        trip.setVehicleId(vehicleId);
        trip.setStatus(status);
        trip.setRemainingDistanceToDestination(remainingDistance);

        // Create mock vehicle
        Vehicle vehicle = new Vehicle();
        vehicle.setId(vehicleId);
        vehicle.setLicensePlate(licensePlate);
        // Only set companyId if provided (null means use global topic)
        if (companyId != null) {
            vehicle.setCompanyId(companyId);
        }
        vehicle.setCompanyName("Test Company");
        vehicle.setCapacity(50);
        trip.setVehicle(vehicle);

        // Create mock route with origin and destination
        Route route = new Route();
        route.setId(1);

        // Create origin location
        Location origin = new Location();
        origin.setId(1);
        origin.setCustomName("Times Square");
        origin.setGooglePlaceName("Times Square, New York, NY, USA");
        origin.setLatitude(40.7580);
        origin.setLongitude(-73.9855);
        route.setOrigin(origin);

        // Create destination location
        Location destination = new Location();
        destination.setId(2);
        destination.setCustomName("Central Park");
        destination.setGooglePlaceName("Central Park, New York, NY, USA");
        destination.setLatitude(40.7829);
        destination.setLongitude(-73.9654);
        route.setDestination(destination);

        trip.setRoute(route);

        return trip;
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