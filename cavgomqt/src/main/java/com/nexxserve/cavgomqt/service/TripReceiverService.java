package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomqt.dto.*;
import com.nexxserve.cavgomqt.dto.incoming.*;
import com.nexxserve.cavgomqt.dto.naviga.NavigaGpsUpdateRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
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
    private NavigaService navigaService;

    /**
     * Process incoming trip event message from MQTT
     * 
     * @param topic   MQTT topic (e.g., "car/3/trip/updates")
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

            // Extract location only; ignore non-location trip data
            Trip tripData = convertTripData(incomingMessage.getData());
            if (tripData == null) {
                logger.warn("⚠️ No trip data present; skipping Naviga update");
                return;
            }

            Double latitude = tripData.getCurrentLatitude();
            Double longitude = tripData.getCurrentLongitude();

            if (latitude == null || longitude == null) {
                logger.info("ℹ️ Trip event has no location; skipping Naviga forwarding (event={})", incomingMessage.getEvent());
                return;
            }

            // Always use current time to avoid back-dated GPS writes
            Long eventTimestamp = System.currentTimeMillis();

            NavigaGpsUpdateRequest gpsUpdate = new NavigaGpsUpdateRequest();
            gpsUpdate.setLatitude(latitude);
            gpsUpdate.setLongitude(longitude);
            if (tripData.getCurrentSpeed() != null) {
                gpsUpdate.setSpeed(tripData.getCurrentSpeed());
            }
            gpsUpdate.setTimestamp(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(eventTimestamp)));

            logger.info("📡 Forwarding MQTT trip location to Naviga (carId={}, lat={}, lon={}, ts={})",
                    carId, latitude, longitude, eventTimestamp);

            // NavigaService will publish the resulting trip update fanout event
            navigaService.updateGpsBatch(carId, Collections.singletonList(gpsUpdate));

            logger.info("✅ Finished location-only processing for trip event: {}", incomingMessage.getEvent());

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
     * 
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
                logger.warn(
                        "⚠️ Topic format doesn't match expected pattern. Expected: car/{carId}/trip/updates, Got: {}",
                        topic);
            }
        } catch (Exception e) {
            logger.error("❌ Error extracting car ID from topic: {}", topic, e);
        }
        return null;
    }

    /**
     * Convert incoming trip data to internal Trip DTO
     */
    private Trip convertTripData(TripEventData incomingData) {
        if (incomingData == null) {
            return null;
        }

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
        route.setOriginId(incoming.getOriginId());
        route.setDestinationId(incoming.getDestinationId());

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
        if (status == null)
            return null;

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
        if (connectionMode == null)
            return null;

        try {
            return ConnectionMode.valueOf(connectionMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown connection mode: {}, defaulting to null", connectionMode);
            return null;
        }
    }

}
