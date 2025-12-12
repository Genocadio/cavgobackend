package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.nexxserve.cavgomqt.dto.*;
import com.nexxserve.cavgomqt.dto.incoming.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for receiving and processing trip event messages from MQTT
 * Handles deserialization and conversion from incoming format to internal DTOs
 */
@Service
public class TripReceiverService {

    private static final Logger logger = LoggerFactory.getLogger(TripReceiverService.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    @Autowired
    private RabbitMQTripPublisherService rabbitMQTripPublisherService;

    @Autowired
    private TripNotificationService tripNotificationService;

    @Autowired
    private RabbitMQVehicleLocationPublisherService vehicleLocationPublisherService;

    /**
     * Process incoming trip event message from MQTT
     * @param topic MQTT topic (e.g., "car/3/trip/updates")
     * @param payload JSON payload
     */
    public void processTripEventMessage(String topic, String payload) {
        try {
            logger.info("=== TRIP UPDATE ===");
            logger.info("Topic: {}", topic);
            logger.info("Payload: {}", payload);

            // Extract car ID from topic (car/{carId}/trip/updates)
            String carId = extractCarIdFromTopic(topic);
            if (carId == null) {
                logger.error("❌ Could not extract car ID from topic: {}", topic);
                return;
            }
            logger.info("✅ Extracted car ID: {}", carId);

            // Deserialize incoming message
            logger.info("🔄 Deserializing incoming message...");
            IncomingTripEventMessage incomingMessage = objectMapper.readValue(payload, IncomingTripEventMessage.class);
            logger.info("✅ Successfully deserialized message with event: {}", incomingMessage.getEvent());
            
            // Convert to internal format
            logger.info("🔄 Converting to internal format...");
            TripEventMessage internalMessage = convertToInternalFormat(incomingMessage);
            logger.info("✅ Successfully converted to internal format");
            
            // Check if trip has location data and publish vehicle location update
            checkAndPublishVehicleLocation(carId, internalMessage.getData());
            
            // Process the trip event
            logger.info("🔄 Processing trip event...");
            processTripEvent(carId, internalMessage);
            logger.info("✅ Successfully processed trip event");
            
            // Publish to RabbitMQ for further processing
            logger.info("🔄 Publishing to RabbitMQ...");
            publishToRabbitMQ(internalMessage, topic, carId);
            logger.info("✅ Successfully published to RabbitMQ");
            
            logger.info("✅ Successfully processed trip event: {} for car: {}", 
                       internalMessage.getEvent(), carId);

        } catch (JsonProcessingException e) {
            logger.error("❌ Failed to deserialize trip event message: {}", e.getMessage());
            logger.error("Payload: {}", payload);
            e.printStackTrace();
        } catch (Exception e) {
            logger.error("❌ Error processing trip event message: {}", e.getMessage(), e);
            e.printStackTrace();
        }
    }

    /**
     * Extract car ID from MQTT topic
     * @param topic MQTT topic (e.g., "car/3/trip/updates")
     * @return car ID or null if extraction fails
     */
    private String extractCarIdFromTopic(String topic) {
        try {
            logger.info("🔍 Extracting car ID from topic: '{}'", topic);
            // Topic format: car/{carId}/trip/updates
            String[] parts = topic.split("/");
            logger.info("🔍 Topic parts: {}", java.util.Arrays.toString(parts));
            
            if (parts.length >= 2 && "car".equals(parts[0])) {
                String carId = parts[1];
                logger.info("✅ Successfully extracted car ID: {}", carId);
                return carId;
            } else {
                logger.warn("⚠️ Topic format doesn't match expected pattern. Expected: car/{carId}/trip/updates, Got: {}", topic);
            }
        } catch (Exception e) {
            logger.error("❌ Error extracting car ID from topic: {}", topic, e);
        }
        return null;
    }

    /**
     * Convert incoming trip event message to internal format
     */
    private TripEventMessage convertToInternalFormat(IncomingTripEventMessage incoming) {
        TripEventMessage internal = new TripEventMessage();
        internal.setEvent(incoming.getEvent());
        
        // Convert trip data
        Trip trip = convertTripData(incoming.getData());
        internal.setData(trip);
        
        return internal;
    }

    /**
     * Convert incoming trip data to internal Trip DTO
     */
    private Trip convertTripData(TripEventData incomingData) {
        Trip trip = new Trip();
        
        // Basic trip information
        trip.setId(incomingData.getId());
        trip.setRouteId(incomingData.getRouteId());
        trip.setVehicleId(incomingData.getVehicleId());
        trip.setStatus(convertTripStatus(incomingData.getStatus()));
        trip.setDepartureTime(incomingData.getDepartureTime());
        trip.setCompletionTime(incomingData.getCompletionTime());
        trip.setConnectionMode(convertConnectionMode(incomingData.getConnectionMode()));
        trip.setNotes(incomingData.getNotes());
        trip.setSeats(incomingData.getSeats());
        trip.setRemainingTimeToDestination(incomingData.getRemainingTimeToDestination());
        // Convert Double to Long for remaining distance
        if (incomingData.getRemainingDistanceToDestination() != null) {
            trip.setRemainingDistanceToDestination(incomingData.getRemainingDistanceToDestination().longValue());
        }
        trip.setIsReversed(incomingData.getIsReversed());
        trip.setCurrentSpeed(incomingData.getCurrentSpeed());
        trip.setCurrentLatitude(incomingData.getCurrentLatitude());
        trip.setCurrentLongitude(incomingData.getCurrentLongitude());
        trip.setHasCustomWaypoints(incomingData.getHasCustomWaypoints());
        trip.setCreatedAt(incomingData.getCreatedAt());
        trip.setUpdatedAt(incomingData.getUpdatedAt());
        
        // Convert vehicle data
        if (incomingData.getVehicle() != null) {
            trip.setVehicle(convertVehicleData(incomingData.getVehicle()));
        }
        
        // Convert route data
        if (incomingData.getRoute() != null) {
            trip.setRoute(convertRouteData(incomingData.getRoute()));
        }
        
        // Convert waypoints
        if (incomingData.getWaypoints() != null) {
            List<TripWaypoint> waypoints = incomingData.getWaypoints().stream()
                    .map(this::convertWaypointData)
                    .collect(Collectors.toList());
            trip.setWaypoints(waypoints);
        }
        
        return trip;
    }

    /**
     * Convert incoming vehicle data to internal Vehicle DTO
     */
    private Vehicle convertVehicleData(IncomingVehicleData incoming) {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(incoming.getId());
        vehicle.setCompanyId(incoming.getCompanyId());
        vehicle.setCompanyName(incoming.getCompanyName());
        vehicle.setCapacity(incoming.getCapacity());
        vehicle.setLicensePlate(incoming.getLicensePlate());
        
        if (incoming.getDriver() != null) {
            vehicle.setDriver(convertDriverData(incoming.getDriver()));
        }
        
        return vehicle;
    }

    /**
     * Convert incoming driver data to internal Driver DTO
     */
    private Driver convertDriverData(IncomingDriverData incoming) {
        Driver driver = new Driver();
        driver.setName(incoming.getName());
        driver.setPhone(incoming.getPhone());
        return driver;
    }

    /**
     * Convert incoming route data to internal Route DTO
     */
    private Route convertRouteData(IncomingRouteData incoming) {
        Route route = new Route();
        route.setId(incoming.getId());
        
        if (incoming.getOrigin() != null) {
            route.setOrigin(convertLocationData(incoming.getOrigin()));
        }
        
        if (incoming.getDestination() != null) {
            route.setDestination(convertLocationData(incoming.getDestination()));
        }
        
        return route;
    }

    /**
     * Convert incoming location data to internal Location DTO
     */
    private Location convertLocationData(IncomingLocationData incoming) {
        Location location = new Location();
        location.setId(incoming.getId());
        location.setLatitude(incoming.getLatitude());
        location.setLongitude(incoming.getLongitude());
        location.setCode(incoming.getCode());
        location.setGooglePlaceName(incoming.getGooglePlaceName());
        location.setCustomName(incoming.getCustomName());
        location.setPlaceId(incoming.getPlaceId());
        location.setCreatedAt(incoming.getCreatedAt());
        location.setUpdatedAt(incoming.getUpdatedAt());
        return location;
    }

    /**
     * Convert incoming waypoint data to internal TripWaypoint DTO
     */
    private TripWaypoint convertWaypointData(IncomingWaypointData incoming) {
        TripWaypoint waypoint = new TripWaypoint();
        waypoint.setId(incoming.getId());
        waypoint.setTripId(incoming.getTripId());
        waypoint.setLocationId(incoming.getLocationId());
        waypoint.setOrder(incoming.getOrder());
        waypoint.setPrice(incoming.getPrice());
        waypoint.setIsPassed(incoming.getIsPassed());
        waypoint.setIsNext(incoming.getIsNext());
        waypoint.setPassedTimestamp(incoming.getPassedTimestamp());
        waypoint.setRemainingTime(incoming.getRemainingTime());
        // Convert Double to Long for remaining distance
        if (incoming.getRemainingDistance() != null) {
            waypoint.setRemainingDistance(incoming.getRemainingDistance().longValue());
        }
        waypoint.setIsCustom(incoming.getIsCustom());
        waypoint.setCreatedAt(incoming.getCreatedAt());
        waypoint.setUpdatedAt(incoming.getUpdatedAt());
        
        if (incoming.getLocation() != null) {
            waypoint.setLocation(convertLocationData(incoming.getLocation()));
        }
        
        return waypoint;
    }

    /**
     * Convert string status to TripStatus enum
     */
    private TripStatus convertTripStatus(String status) {
        if (status == null) return null;
        
        try {
            return TripStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown trip status: {}, defaulting to null", status);
            return null;
        }
    }

    /**
     * Convert string connection mode to ConnectionMode enum
     */
    private ConnectionMode convertConnectionMode(String connectionMode) {
        if (connectionMode == null) return null;
        
        try {
            return ConnectionMode.valueOf(connectionMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown connection mode: {}, defaulting to null", connectionMode);
            return null;
        }
    }

    /**
     * Process the converted trip event
     * This is where you can add business logic for handling different trip events
     */
    private void processTripEvent(String carId, TripEventMessage tripEvent) {
        String event = tripEvent.getEvent();
        Trip tripData = tripEvent.getData();
        
        logger.info("Processing trip event: {} for car: {}", event, carId);
        
        switch (event) {
            case "TRIP_STARTED":
            case "trip_started":
                handleTripStarted(carId, tripData);
                break;
            case "TRIP_COMPLETED":
            case "trip_completed":
                handleTripCompleted(carId, tripData);
                break;
            case "TRIP_CANCELLED":
            case "trip_cancelled":
                handleTripCancelled(carId, tripData);
                break;
            case "TRIP_UPDATED":
            case "trip_updated":
            case "TRIP_PROGRESS_UPDATE":
            case "trip_progress_update":
            case "progress_update":
                handleTripUpdated(carId, tripData);
                break;
            default:
                logger.info("Unhandled trip event: {}", event);
                // Even for unhandled events, check trip status and send notifications if needed
                handleTripUpdated(carId, tripData);
                break;
        }
    }

    private void handleTripStarted(String carId, Trip tripData) {
        logger.info("🚗 Trip started for car: {}, trip ID: {}", carId, tripData.getId());
        // Update vehicle registry with active trip
        if (tripData.getId() != null) {
            vehicleRegistryService.setActiveTrip(Long.valueOf(carId), tripData.getId().toString());
        }
    }

    private void handleTripCompleted(String carId, Trip tripData) {
        logger.info("✅ Trip completed for car: {}, trip ID: {}", carId, tripData.getId());
        // Clear active trip from vehicle registry
        vehicleRegistryService.clearActiveTrip(Long.valueOf(carId));
        // Send completion notification
        tripNotificationService.sendCompletionNotification(tripData);
    }

    private void handleTripCancelled(String carId, Trip tripData) {
        logger.info("❌ Trip cancelled for car: {}, trip ID: {}", carId, tripData.getId());
        // Clear active trip from vehicle registry
        vehicleRegistryService.clearActiveTrip(Long.valueOf(carId));
    }

    private void handleTripUpdated(String carId, Trip tripData) {
        logger.info("🔄 Trip updated for car: {}, trip ID: {}", carId, tripData.getId());
        logger.info("📊 Trip status: {}, Remaining distance: {}m", 
                   tripData.getStatus(), tripData.getRemainingDistanceToDestination());
        
        // Check trip status first - if completed, send completion notification
        if (tripData.getStatus() == TripStatus.COMPLETED) {
            logger.info("📢 Trip status is COMPLETED, sending completion notification");
            handleTripCompleted(carId, tripData);
            return;
        }
        
        // Update trip information in registry or database
        // This could include location updates, status changes, etc.
        // Check and send "about to complete" notification if conditions are met
        logger.info("🔔 Checking if 'about to complete' notification should be sent...");
        tripNotificationService.checkAndSendAboutToCompleteNotification(tripData);
    }

    /**
     * Check if trip data has location information and publish vehicle location update
     * @param carId The car ID
     * @param tripData The trip data containing location information
     */
    private void checkAndPublishVehicleLocation(String carId, Trip tripData) {
        if (tripData == null) {
            return;
        }
        
        // Check if latitude and longitude are not null
        if (tripData.getCurrentLatitude() != null && tripData.getCurrentLongitude() != null) {
            logger.info("📍 Trip data contains location: ({}, {})", 
                       tripData.getCurrentLatitude(), tripData.getCurrentLongitude());
            
            // Create vehicle location update message
            VehicleLocationUpdateMessage locationMsg = new VehicleLocationUpdateMessage();
            locationMsg.setCarId(carId);
            locationMsg.setStatus("ONLINE");
            locationMsg.setTimestamp(System.currentTimeMillis());
            locationMsg.setCurrentLatitude(tripData.getCurrentLatitude());
            locationMsg.setCurrentLongitude(tripData.getCurrentLongitude());
            
            // Include speed if available
            if (tripData.getCurrentSpeed() != null) {
                locationMsg.setCurrentSpeed(tripData.getCurrentSpeed());
            }
            
            // Accuracy and bearing are not available in trip data, leave as null
            
            // Publish to RabbitMQ
            try {
                vehicleLocationPublisherService.publish(locationMsg);
                logger.info("✅ Published vehicle location update from trip data for car: {}", carId);
            } catch (Exception e) {
                logger.error("❌ Failed to publish vehicle location update from trip data: {}", e.getMessage(), e);
            }
        } else {
            logger.debug("📍 Trip data does not contain location information (lat: {}, lng: {})", 
                        tripData.getCurrentLatitude(), tripData.getCurrentLongitude());
        }
    }

    /**
     * Publish trip event to RabbitMQ for further processing
     * @param tripEventMessage The converted trip event message
     * @param originalTopic The original MQTT topic
     * @param carId The car ID
     */
    private void publishToRabbitMQ(TripEventMessage tripEventMessage, String originalTopic, String carId) {
        try {
            logger.info("📤 Publishing trip event to RabbitMQ: {} for car: {}", 
                       tripEventMessage.getEvent(), carId);
            
            // Check if RabbitMQ connection is available
            if (!rabbitMQTripPublisherService.isConnectionAvailable()) {
                logger.warn("⚠️ RabbitMQ connection not available, skipping trip event publication");
                return;
            }
            
            // Publish with metadata
            rabbitMQTripPublisherService.publishTripEventWithMetadata(tripEventMessage, originalTopic, carId);
            
            logger.info("✅ Successfully published trip event to RabbitMQ");
            
        } catch (Exception e) {
            logger.error("❌ Failed to publish trip event to RabbitMQ: {}", e.getMessage(), e);
            // Don't throw the exception to avoid breaking the MQTT processing flow
            // The trip event was already processed locally, so we just log the RabbitMQ publishing failure
        }
    }
}
