package com.nexxserve.cavgomain.messaging;

import com.nexxserve.cavgomain.config.RabbitMQConfig;
import com.nexxserve.cavgomain.dto.message.VehicleLocationMessage;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleLocation;
import com.nexxserve.cavgomain.repository.VehicleLocationRepository;
import com.nexxserve.cavgomain.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class VehicleLocationListener {

    private final VehicleRepository vehicleRepository;
    private final VehicleLocationRepository locationRepository;

    @RabbitListener(queues = RabbitMQConfig.VEHICLE_LOCATION_QUEUE)
    @Transactional
    public void handleLocationAndStatusUpdate(VehicleLocationMessage message) {
        try {
            log.info("Received update for vehicle ID: {} with status: {}", 
                    message.getCarId(), message.getStatus());
            
            // Find vehicle by ID
            Long vehicleId = Long.parseLong(message.getCarId());
            Vehicle vehicle = vehicleRepository.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Vehicle not found with ID: " + message.getCarId()));

            // Process based on status
            if ("OFFLINE".equalsIgnoreCase(message.getStatus())) {
                // Vehicle went offline - just update the timestamp
                // Don't save location data as it's null
                vehicle.setLastOnlineAt(LocalDateTime.now());
                vehicleRepository.save(vehicle);
                log.info("Vehicle {} marked as OFFLINE", message.getCarId());
                
            } else if ("ONLINE".equalsIgnoreCase(message.getStatus()) || 
                       "READY".equalsIgnoreCase(message.getStatus())) {
                // Vehicle is online/ready - save location data
                // ONLINE = actively transmitting location
                // READY = powered on, GPS fixed, ready for assignment
                
                // Only save location if we have valid coordinates
                if (message.getCurrentLatitude() != null && message.getCurrentLongitude() != null) {
                    VehicleLocation location = new VehicleLocation();
                    location.setVehicle(vehicle);
                    location.setLatitude(message.getCurrentLatitude());
                    location.setLongitude(message.getCurrentLongitude());
                    
                    // All optional fields - save null if not provided
                    location.setSpeed(message.getCurrentSpeed());
                    location.setAccuracy(message.getAccuracy());
                    location.setBearing(message.getBearing());
                    location.setTimestamp(message.getTimestamp());
                    location.setRecordedAt(LocalDateTime.now());
                    
                    locationRepository.save(location);
                    log.info("Saved location for vehicle {} with status {}: lat={}, lng={}, speed={}", 
                            message.getCarId(),
                            message.getStatus(),
                            message.getCurrentLatitude(), 
                            message.getCurrentLongitude(),
                            message.getCurrentSpeed());
                }

                // Update vehicle's last online timestamp
                vehicle.setLastOnlineAt(LocalDateTime.now());
                vehicleRepository.save(vehicle);
                
            } else {
                log.warn("Unknown status '{}' for vehicle {}", message.getStatus(), message.getCarId());
            }

        } catch (NumberFormatException e) {
            log.error("Invalid car_id format: {}", message.getCarId(), e);
        } catch (Exception e) {
            log.error("Error processing update for vehicle: {}", message.getCarId(), e);
        }
    }
}

