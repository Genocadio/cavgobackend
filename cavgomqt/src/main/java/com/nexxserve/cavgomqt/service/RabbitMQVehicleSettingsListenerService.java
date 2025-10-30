package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomqt.dto.VehicleSettingsMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service to listen for vehicle settings changes from RabbitMQ and forward them to MQTT
 * 
 * Listens to: vehicle.settings.queue
 * Exchange: vehicle.settings.exchange
 * Routing Key Pattern: vehicle.settings.{vehicleId}
 * 
 * Publishes to MQTT: car/{vehicleId}/settings
 */
@Service
public class RabbitMQVehicleSettingsListenerService {

    @Autowired
    private MqttService mqttService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Listen for vehicle settings messages from RabbitMQ
     * 
     * @param message The RabbitMQ message containing vehicle settings
     */
    @RabbitListener(queues = "vehicle.settings.queue")
    public void handleVehicleSettings(Message message) {
        try {
            System.out.println("📥 === RECEIVED VEHICLE SETTINGS FROM RABBITMQ ===");
            System.out.println("  - Timestamp: " + System.currentTimeMillis());
            
            // Get routing key to extract vehicle ID
            String routingKey = message.getMessageProperties().getReceivedRoutingKey();
            System.out.println("  - Routing Key: " + routingKey);
            
            // Extract vehicle ID from routing key (vehicle.settings.{vehicleId})
            Long vehicleId = extractVehicleIdFromRoutingKey(routingKey);
            if (vehicleId == null) {
                System.err.println("❌ Could not extract vehicle ID from routing key: " + routingKey);
                return;
            }
            
            System.out.println("  - Vehicle ID: " + vehicleId);
            
            // Parse message body
            String payload = new String(message.getBody());
            System.out.println("  - Payload: " + payload);
            
            VehicleSettingsMessage settings = objectMapper.readValue(payload, VehicleSettingsMessage.class);
            System.out.println("✅ Successfully parsed vehicle settings:");
            System.out.println("  - License Plate: " + settings.getLicensePlate());
            System.out.println("  - Logout: " + settings.getLogout());
            System.out.println("  - Devmode: " + settings.getDevmode());
            System.out.println("  - Deactivate: " + settings.getDeactivate());
            System.out.println("  - Appmode: " + settings.getAppmode());
            System.out.println("  - Simulate: " + settings.getSimulate());
            
            // Forward to MQTT
            mqttService.publishVehicleSettings(vehicleId, settings);
            System.out.println("✅ Successfully forwarded vehicle settings to MQTT");
            
        } catch (Exception e) {
            System.err.println("❌ FAILED to process vehicle settings from RabbitMQ:");
            System.err.println("  - Error: " + e.getMessage());
            System.err.println("  - Message: " + new String(message.getBody()));
            e.printStackTrace();
        }
    }

    /**
     * Extract vehicle ID from routing key
     * Expected format: vehicle.settings.{vehicleId}
     * Example: vehicle.settings.17 → 17
     * 
     * @param routingKey The routing key from RabbitMQ
     * @return The vehicle ID, or null if not found
     */
    private Long extractVehicleIdFromRoutingKey(String routingKey) {
        if (routingKey == null || !routingKey.startsWith("vehicle.settings.")) {
            return null;
        }
        
        try {
            String[] parts = routingKey.split("\\.");
            if (parts.length >= 3) {
                return Long.parseLong(parts[2]);
            }
        } catch (NumberFormatException e) {
            System.err.println("❌ Invalid vehicle ID in routing key: " + routingKey);
        }
        
        return null;
    }
}

