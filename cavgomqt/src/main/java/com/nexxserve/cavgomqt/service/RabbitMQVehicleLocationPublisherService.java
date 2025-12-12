package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.VehicleLocationUpdateMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service to publish vehicle location/status updates to RabbitMQ
 * Exchange: vehicle.location.updates.fanout (FanoutExchange)
 * Multiple services can bind their queues to this exchange to receive updates
 */
@Service
public class RabbitMQVehicleLocationPublisherService {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE_NAME = "vehicle.location.updates.fanout";

    /**
     * Publish vehicle location/status update to RabbitMQ
     * 
     * Note: All location fields (latitude, longitude, speed, accuracy, bearing) are optional.
     * The receiving service should handle null values appropriately.
     * 
     * @param message The vehicle location update message
     */
    public void publish(VehicleLocationUpdateMessage message) {
        try {
            boolean hasLocation = message.getCurrentLatitude() != null && message.getCurrentLongitude() != null;
            
            System.out.println("📤 === PUBLISHING VEHICLE LOCATION TO RABBITMQ ===");
            System.out.println("  - Exchange: " + EXCHANGE_NAME + " (Fanout)");
            System.out.println("  - Car ID: " + message.getCarId());
            System.out.println("  - Status: " + message.getStatus());
            System.out.println("  - Timestamp: " + message.getTimestamp());
            
            if (hasLocation) {
                System.out.println("  - Location: (" + message.getCurrentLatitude() + ", " + message.getCurrentLongitude() + ")");
                System.out.println("  - Speed: " + (message.getCurrentSpeed() != null ? message.getCurrentSpeed() + " km/h" : "not provided"));
                System.out.println("  - Accuracy: " + (message.getAccuracy() != null ? message.getAccuracy() + " m" : "not provided"));
                System.out.println("  - Bearing: " + (message.getBearing() != null ? message.getBearing() + "°" : "not provided"));
            } else {
                System.out.println("  - Location: NOT PROVIDED (status update only)");
            }
            
            // Publish to fanout exchange - routing key is ignored for fanout exchanges
            // Let RabbitTemplate's Jackson converter handle serialization automatically
            // This sets the correct __TypeId__ header for proper deserialization
            rabbitTemplate.convertAndSend(EXCHANGE_NAME, "", message);
            
            System.out.println("✅ SUCCESS: Vehicle " + (hasLocation ? "location" : "status") + " published to RabbitMQ");
            System.out.println("  - Car ID: " + message.getCarId());
            System.out.println("  - Status: " + message.getStatus());
            
        } catch (Exception e) {
            System.err.println("❌ FAILED to publish vehicle location to RabbitMQ:");
            System.err.println("  - Error: " + e.getMessage());
            System.err.println("  - Car ID: " + (message != null ? message.getCarId() : "null"));
            System.err.println("  - Status: " + (message != null ? message.getStatus() : "null"));
            e.printStackTrace();
        }
    }

    /**
     * Create and publish a vehicle status message
     * This is a convenience method for simple status updates
     */
    public void publishStatus(String carId, String status, Long timestamp) {
        VehicleLocationUpdateMessage message = new VehicleLocationUpdateMessage();
        message.setCarId(carId);
        message.setStatus(status);
        message.setTimestamp(timestamp);
        // Location fields will be null for simple status updates
        publish(message);
    }

    /**
     * Create and publish a full vehicle location update
     */
    public void publishLocationUpdate(String carId, String status, Long timestamp,
                                      Double latitude, Double longitude,
                                      Double speed, Double accuracy, Double bearing) {
        VehicleLocationUpdateMessage message = new VehicleLocationUpdateMessage();
        message.setCarId(carId);
        message.setStatus(status);
        message.setTimestamp(timestamp);
        message.setCurrentLatitude(latitude);
        message.setCurrentLongitude(longitude);
        message.setCurrentSpeed(speed);
        message.setAccuracy(accuracy);
        message.setBearing(bearing);
        publish(message);
    }
}

