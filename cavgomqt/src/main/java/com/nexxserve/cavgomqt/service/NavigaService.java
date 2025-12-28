package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.Location;
import com.nexxserve.cavgomqt.dto.TripWaypoint;
import com.nexxserve.cavgomqt.dto.naviga.NavigaTripRequest;
import com.nexxserve.cavgomqt.dto.naviga.NavigaWaypoint;
import com.nexxserve.cavgomqt.dto.naviga.NavigaGpsUpdateRequest;
import com.nexxserve.cavgomqt.dto.naviga.NavigaTripUpdateEvent;
import com.nexxserve.cavgomqt.entity.NavigaTripEntity;
import com.nexxserve.cavgomqt.repository.NavigaTripRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for integrating with Naviga API
 * Sends trip creation events and GPS location updates to Naviga
 */
@Service
public class NavigaService {

    private static final Logger logger = LoggerFactory.getLogger(NavigaService.class);

    @Value("${naviga.base.url:}")
    private String navigaBaseUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private NavigaTripRepository navigaTripRepository;

    @Autowired
    private RabbitMQNavigaTripUpdatePublisher rabbitMQNavigaTripUpdatePublisher;

    /**
     * Check if Naviga integration is enabled
     * 
     * @return true if naviga.base.url is configured (not empty)
     */
    private boolean isEnabled() {
        return navigaBaseUrl != null && !navigaBaseUrl.trim().isEmpty();
    }

    @javax.annotation.PostConstruct
    public void init() {
        if (isEnabled()) {
            logger.info("✅ Naviga Integration ENABLED - Url: {}", navigaBaseUrl);
        } else {
            logger.warn("⚠️ Naviga Integration DISABLED - naviga.base.url is not set");
        }
    }

    /**
     * Create a trip in Naviga API
     * 
     * @param trip The trip data to send
     */
    public void createTrip(Trip trip) {
        if (!isEnabled()) {
            logger.debug("Naviga integration disabled (naviga.base.url not configured)");
            return;
        }

        if (trip == null) {
            logger.warn("⚠️ Cannot create trip in Naviga: trip is null");
            return;
        }

        try {
            // Validate required data
            if (trip.getRoute() == null) {
                logger.warn("⚠️ Cannot create trip in Naviga: trip route is null");
                return;
            }

            if (trip.getRoute().getOrigin() == null || trip.getRoute().getDestination() == null) {
                logger.warn("⚠️ Cannot create trip in Naviga: origin or destination is null");
                return;
            }

            if (trip.getVehicle() == null || trip.getVehicle().getLicensePlate() == null) {
                logger.warn("⚠️ Cannot create trip in Naviga: vehicle or license plate is null");
                return;
            }

            if (trip.getVehicleId() == null) {
                logger.warn("⚠️ Cannot create trip in Naviga: vehicle ID is null");
                return;
            }

            // Build waypoints: origin -> waypoints (sorted by order) -> destination
            List<NavigaWaypoint> waypoints = new ArrayList<>();

            // Add origin with ID from route.originId
            Location origin = trip.getRoute().getOrigin();
            String originName = origin.getCustomName() != null ? origin.getCustomName() : origin.getGooglePlaceName();
            Integer originId = trip.getRoute().getOriginId() != null ? trip.getRoute().getOriginId() : origin.getId();
            waypoints.add(new NavigaWaypoint(originId, origin.getLatitude(), origin.getLongitude(), originName));

            // Add intermediate waypoints (sorted by order)
            if (trip.getWaypoints() != null && !trip.getWaypoints().isEmpty()) {
                List<TripWaypoint> sortedWaypoints = trip.getWaypoints().stream()
                        .sorted(Comparator.comparing(TripWaypoint::getOrder,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .collect(Collectors.toList());

                for (TripWaypoint tripWaypoint : sortedWaypoints) {
                    if (tripWaypoint.getLocation() != null) {
                        Location location = tripWaypoint.getLocation();
                        String waypointName = location.getCustomName() != null ? location.getCustomName()
                                : location.getGooglePlaceName();
                        // Use waypoint ID if available, otherwise use location ID
                        Integer waypointId = tripWaypoint.getId() != null ? tripWaypoint.getId() : location.getId();
                        waypoints
                                .add(new NavigaWaypoint(waypointId, location.getLatitude(), location.getLongitude(), waypointName));
                    }
                }
            }

            // Add destination with ID from route.destinationId
            Location destination = trip.getRoute().getDestination();
            String destinationName = destination.getCustomName() != null ? destination.getCustomName()
                    : destination.getGooglePlaceName();
            Integer destinationId = trip.getRoute().getDestinationId() != null ? trip.getRoute().getDestinationId() : destination.getId();
            waypoints.add(new NavigaWaypoint(destinationId, destination.getLatitude(), destination.getLongitude(), destinationName));

            // Build request
            NavigaTripRequest request = new NavigaTripRequest();
            request.setId(Long.valueOf(trip.getId())); // Use trip ID if available
            request.setCarId(String.valueOf(trip.getVehicleId())); // Send raw ID (e.g., "4")
            // request.setPlateNumber(trip.getVehicle().getLicensePlate()); // Removed
            request.setWaypoints(waypoints);

            // Set default flags
            request.setIncludeInstructions(false); // Default to false
            request.setIncludeOrigin(false); // Default to false
            request.setCityTrip(false); // Default to false

            // Make API call
            String url = navigaBaseUrl + "/api/trips";
            try {
                String jsonRequest = objectMapper.writeValueAsString(request);
                logger.info("📤 Sending trip creation to Naviga API: {}", url);
                logger.info("📝 Request Body: {}", jsonRequest);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                logger.error("Failed to serialize request", e);
            }
            logger.debug("Trip request: carId={}, waypoints={}",
                    request.getCarId(), waypoints.size());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<NavigaTripRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class);

            if (response.getStatusCode() == HttpStatus.CREATED) {
                logger.info("✅ Successfully created trip in Naviga: carId={}, tripId={}",
                        request.getCarId(), trip.getId());
                
                // Store trip in database registry
                try {
                    // Remove any existing trip for this car first (in case of conflict)
                    navigaTripRepository.deleteByCarId(request.getCarId());
                    
                    NavigaTripEntity tripEntity = new NavigaTripEntity();
                    tripEntity.setTripId(Long.valueOf(trip.getId()));
                    tripEntity.setCarId(request.getCarId());
                    navigaTripRepository.save(tripEntity);
                    logger.info("💾 Stored trip in database: carId={}, tripId={}", 
                        request.getCarId(), trip.getId());
                } catch (Exception dbError) {
                    logger.error("❌ Failed to store trip in database: {}", dbError.getMessage());
                }
                
                // Publish trip update event to RabbitMQ fanout exchange
                try {
                    // Parse full response to extract trip data, waypointProgresses, and currentLocation
                    NavigaTripUpdateEvent.NavigaTripDto tripDto = parseNavigaTripResponse(response.getBody());
                    if (tripDto == null) {
                        // Fallback to minimal data if parsing fails
                        tripDto = new NavigaTripUpdateEvent.NavigaTripDto(
                                Long.valueOf(trip.getId()),
                                request.getCarId(),
                                "CREATED");
                    }
                    NavigaTripUpdateEvent event = new NavigaTripUpdateEvent(tripDto, "naviga-trip-create");
                    rabbitMQNavigaTripUpdatePublisher.publishTripUpdateEvent(event);
                    logger.info("📤 Published trip creation event to cavgomqt.trip.updates fanout");
                } catch (Exception pubError) {
                    logger.warn("⚠️ Failed to publish trip creation event: {}", pubError.getMessage());
                }
            } else {
                logger.warn("⚠️ Unexpected response from Naviga API: status={}, body={}",
                        response.getStatusCode(), response.getBody());
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                logger.warn("⚠️ Naviga API returned 409 Conflict - Car already has an active trip: carId=CAR-{}",
                        trip.getVehicleId());
            } else if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                logger.error("❌ Naviga API returned 400 Bad Request - Invalid request: {}",
                        e.getResponseBodyAsString());
            } else {
                logger.error("❌ Naviga API error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            }
        } catch (RestClientException e) {
            logger.error("❌ Failed to create trip in Naviga API: {}", e.getMessage(), e);
        } catch (Exception e) {
            logger.error("❌ Unexpected error creating trip in Naviga API: {}", e.getMessage(), e);
        }
    }

    /**
     * Update GPS location in Naviga API
     * 
     * @param carId     The car ID (e.g., "17" or "CAR-17")
     * @param latitude  GPS latitude (required)
     * @param longitude GPS longitude (required)
     * @param speed     Speed in km/h (optional)
     * @param heading   Direction in degrees 0-360 (optional)
     * @param accuracy  GPS accuracy in meters (optional)
     * @param timestamp Unix timestamp in milliseconds (optional)
     */
    public void updateGps(String carId, Double latitude, Double longitude,
            Double speed, Double heading, Double accuracy, Long timestamp) {
        if (!isEnabled()) {
            logger.debug("Naviga integration disabled (naviga.base.url not configured)");
            return;
        }

        if (carId == null || latitude == null || longitude == null) {
            logger.debug("⚠️ Cannot update GPS in Naviga: missing required fields (carId, latitude, longitude)");
            return;
        }

        try {
            // Build request (minimal: only lat/lon required)
            NavigaGpsUpdateRequest request = new NavigaGpsUpdateRequest();
            request.setLatitude(latitude);
            request.setLongitude(longitude);

            // Add optional fields if available
            if (speed != null) {
                request.setSpeed(speed);
            }
            if (heading != null) {
                request.setHeading(heading);
            }
            if (accuracy != null) {
                request.setAccuracy(accuracy);
            }
            if (timestamp != null) {
                // Convert Unix timestamp to ISO 8601 format
                Instant instant = Instant.ofEpochMilli(timestamp);
                request.setTimestamp(DateTimeFormatter.ISO_INSTANT.format(instant));
            }

            // Normalize carId format (ensure it's just the ID, not "CAR-{id}")
            String normalizedCarId = carId;
            if (carId.startsWith("CAR-")) {
                normalizedCarId = carId.substring(4);
            }

            request.setCarId(normalizedCarId);

            // Make API call
            String url = navigaBaseUrl + "/api/gps";
            logger.debug("📤 Sending GPS update to Naviga API: {}", url);

            // Wrap in list for batch update endpoint
            List<NavigaGpsUpdateRequest> requestList = java.util.Collections.singletonList(request);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<NavigaGpsUpdateRequest>> entity = new HttpEntity<>(requestList, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                logger.debug("✅ Successfully updated GPS in Naviga: carId={}", normalizedCarId);
            } else {
                logger.warn("⚠️ Unexpected response from Naviga GPS API: status={}, body={}",
                        response.getStatusCode(), response.getBody());
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.debug("⚠️ Naviga API returned 404 Not Found - Car has no active trip: carId={}", carId);
            } else if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                logger.warn("⚠️ Naviga API returned 400 Bad Request - Invalid GPS data: {}",
                        e.getResponseBodyAsString());
            } else {
                logger.warn("⚠️ Naviga GPS API error: status={}, body={}", e.getStatusCode(),
                        e.getResponseBodyAsString());
            }
        } catch (RestClientException e) {
            logger.warn("⚠️ Failed to update GPS in Naviga API: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Unexpected error updating GPS in Naviga API: {}", e.getMessage(), e);
        }
    }

    /**
     * Update GPS locations in batch for Naviga API
     * Sends multiple GPS updates for the same vehicle in a single API call
     * 
     * @param carId            The car ID (e.g., "17" or "CAR-17")
     * @param gpsUpdateRequests List of GPS update requests
     */
    public void updateGpsBatch(String carId, List<NavigaGpsUpdateRequest> gpsUpdateRequests) {
        if (!isEnabled()) {
            logger.debug("Naviga integration disabled (naviga.base.url not configured)");
            return;
        }

        if (carId == null || gpsUpdateRequests == null || gpsUpdateRequests.isEmpty()) {
            logger.debug("⚠️ Cannot update GPS batch in Naviga: missing carId or empty updates list");
            return;
        }

        // Normalize carId format (ensure it's just the ID, not "CAR-{id}")
        String normalizedCarId = carId;
        if (carId.startsWith("CAR-")) {
            normalizedCarId = carId.substring(4);
        }

        // Check if car has an active trip in database before sending GPS updates
        if (!navigaTripRepository.existsByCarId(normalizedCarId)) {
            logger.info("🚫 Skipping GPS batch: carId={} has no active Naviga trip. Updates queued: {}",
                    normalizedCarId, gpsUpdateRequests.size());
            return;
        }

        try {
            // Ensure all requests have the normalized carId
            for (NavigaGpsUpdateRequest request : gpsUpdateRequests) {
                request.setCarId(normalizedCarId);
            }

            // Make API call
            String url = navigaBaseUrl + "/api/gps";
            logger.debug("📤 Sending GPS batch update to Naviga API: {} ({} points)", url, gpsUpdateRequests.size());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<List<NavigaGpsUpdateRequest>> entity = new HttpEntity<>(gpsUpdateRequests, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Log after successful send
                logger.info("✅ Sent {} GPS updates to Naviga API in single batch for carId={}",
                        gpsUpdateRequests.size(), normalizedCarId);

                try {
                    // Parse full response to extract trip data, waypointProgresses, and currentLocation
                    NavigaTripUpdateEvent.NavigaTripDto tripDto = parseNavigaTripResponse(response.getBody());
                    if (tripDto != null) {
                        String status = tripDto.getStatus();
                        Long tripId = tripDto.getId();

                        if (status != null) {
                            logger.info("📝 Naviga trip response: status={} tripId={} carId={}", status, tripId,
                                    normalizedCarId);

                            // Publish GPS update event to RabbitMQ fanout exchange
                            try {
                                NavigaTripUpdateEvent event = new NavigaTripUpdateEvent(tripDto, "naviga-gps-batch");
                                rabbitMQNavigaTripUpdatePublisher.publishTripUpdateEvent(event);
                            } catch (Exception pubError) {
                                logger.warn("⚠️ Failed to publish GPS batch event: {}", pubError.getMessage());
                            }

                            if ("COMPLETED".equalsIgnoreCase(status)) {
                                logger.info("🏁 Trip COMPLETED detected; removing from registry and Naviga: tripId={}",
                                        tripId);
                                // Remove from local registry and delete in Naviga to stop further updates
                                try {
                                    if (tripId != null) {
                                        navigaTripRepository.deleteByTripId(tripId);
                                    } else {
                                        // Fallback: delete by carId
                                        navigaTripRepository.deleteByCarId(normalizedCarId);
                                    }
                                } catch (Exception dbErr) {
                                    logger.error("❌ Failed to remove trip from local registry: {}",
                                            dbErr.getMessage());
                                }
                                try {
                                    if (tripId != null) {
                                        deleteTrip(tripId);
                                    }
                                } catch (Exception delErr) {
                                    logger.warn("⚠️ Failed to delete trip in Naviga after completion: {}",
                                            delErr.getMessage());
                                }
                            } else {
                                logger.info("📡 Trip still active; will continue sending GPS updates");
                            }
                        }
                    }
                } catch (Exception parseErr) {
                    logger.warn("⚠️ Failed to parse Naviga GPS response: {}", parseErr.getMessage());
                }

            } else {
                logger.warn("⚠️ Unexpected response from Naviga GPS API: status={}, body={}",
                        response.getStatusCode(), response.getBody());
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.debug("⚠️ Naviga API returned 404 Not Found - Car has no active trip: carId={}", carId);
                // Remove from database since trip doesn't exist in Naviga
                try {
                    navigaTripRepository.deleteByCarId(normalizedCarId);
                    logger.info("🗑️ Removed stale trip from database: carId={}", normalizedCarId);
                } catch (Exception dbError) {
                    logger.error("❌ Failed to remove trip from database: {}", dbError.getMessage());
                }
            } else if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                logger.warn("⚠️ Naviga API returned 400 Bad Request - Invalid GPS data: {}",
                        e.getResponseBodyAsString());
            } else {
                logger.warn("⚠️ Naviga GPS API error: status={}, body={}", e.getStatusCode(),
                        e.getResponseBodyAsString());
            }
        } catch (RestClientException e) {
            logger.warn("⚠️ Failed to update GPS batch in Naviga API: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Unexpected error updating GPS batch in Naviga API: {}", e.getMessage(), e);
        }
    }

    /**
     * Delete a trip in Naviga API
     * 
     * @param tripId The trip ID to delete
     */
    public void deleteTrip(Long tripId) {
        if (!isEnabled()) {
            logger.debug("Naviga integration disabled (naviga.base.url not configured)");
            return;
        }

        if (tripId == null) {
            logger.warn("⚠️ Cannot delete trip in Naviga: tripId is null");
            return;
        }

        try {
            // Make API call
            String url = navigaBaseUrl + "/api/trips/" + tripId;
            logger.info("🗑️ Deleting trip from Naviga API: {}", url);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.DELETE,
                    null,
                    String.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.NO_CONTENT) {
                logger.info("✅ Successfully deleted trip from Naviga: tripId={}", tripId);
                
                // Remove trip from database registry
                try {
                    navigaTripRepository.deleteByTripId(tripId);
                    logger.info("🗑️ Removed trip from database: tripId={}", tripId);
                } catch (Exception dbError) {
                    logger.error("❌ Failed to remove trip from database: {}", dbError.getMessage());
                }
                
                // Note: Not publishing trip deletion event to RabbitMQ (as per requirement)
                logger.info("ℹ️ Trip deleted from Naviga - no RabbitMQ event published");
            } else {
                logger.warn("⚠️ Unexpected response from Naviga DELETE API: status={}, body={}",
                        response.getStatusCode(), response.getBody());
            }

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                logger.debug("⚠️ Naviga API returned 404 Not Found - Trip doesn't exist: tripId={}", tripId);
                // Still try to remove from database
                try {
                    navigaTripRepository.deleteByTripId(tripId);
                    logger.info("🗑️ Removed stale trip from database: tripId={}", tripId);
                } catch (Exception dbError) {
                    logger.error("❌ Failed to remove trip from database: {}", dbError.getMessage());
                }
            } else {
                logger.warn("⚠️ Naviga DELETE API error: status={}, body={}", e.getStatusCode(),
                        e.getResponseBodyAsString());
            }
        } catch (RestClientException e) {
            logger.warn("⚠️ Failed to delete trip from Naviga API: {}", e.getMessage());
        } catch (Exception e) {
            logger.error("❌ Unexpected error deleting trip from Naviga API: {}", e.getMessage(), e);
        }
    }

    /**
     * Parse Naviga API response and extract trip data including waypointProgresses and currentLocation.
     * 
     * @param responseBody JSON response body from Naviga API
     * @return NavigaTripDto with full trip data, or null if parsing fails
     */
    private NavigaTripUpdateEvent.NavigaTripDto parseNavigaTripResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode tripNode = root.get("trip");
            if (tripNode == null || tripNode.isNull()) {
                return null;
            }

            NavigaTripUpdateEvent.NavigaTripDto tripDto = new NavigaTripUpdateEvent.NavigaTripDto();

            // Basic trip fields
            if (tripNode.has("id") && !tripNode.get("id").isNull()) {
                tripDto.setId(tripNode.get("id").asLong());
            }
            if (tripNode.has("carId") && !tripNode.get("carId").isNull()) {
                tripDto.setCarId(tripNode.get("carId").asText());
            }
            if (tripNode.has("status") && !tripNode.get("status").isNull()) {
                tripDto.setStatus(tripNode.get("status").asText());
            }
            if (tripNode.has("createdAt") && !tripNode.get("createdAt").isNull()) {
                tripDto.setCreatedAt(java.time.Instant.parse(tripNode.get("createdAt").asText()));
            }
            if (tripNode.has("completedAt") && !tripNode.get("completedAt").isNull()) {
                tripDto.setCompletedAt(java.time.Instant.parse(tripNode.get("completedAt").asText()));
            }

            // Parse waypointProgresses array
            if (tripNode.has("waypointProgresses") && tripNode.get("waypointProgresses").isArray()) {
                java.util.List<NavigaTripUpdateEvent.WaypointProgressDto> waypointProgresses = new java.util.ArrayList<>();
                for (JsonNode wpNode : tripNode.get("waypointProgresses")) {
                    NavigaTripUpdateEvent.WaypointProgressDto wpDto = new NavigaTripUpdateEvent.WaypointProgressDto();
                    
                    if (wpNode.has("waypointIndex") && !wpNode.get("waypointIndex").isNull()) {
                        wpDto.setWaypointIndex(wpNode.get("waypointIndex").asInt());
                    }
                    if (wpNode.has("waypointId") && !wpNode.get("waypointId").isNull()) {
                        wpDto.setWaypointId(wpNode.get("waypointId").asText());
                    }
                    if (wpNode.has("waypointName") && !wpNode.get("waypointName").isNull()) {
                        wpDto.setWaypointName(wpNode.get("waypointName").asText());
                    }
                    if (wpNode.has("latitude") && !wpNode.get("latitude").isNull()) {
                        wpDto.setLatitude(wpNode.get("latitude").asDouble());
                    }
                    if (wpNode.has("longitude") && !wpNode.get("longitude").isNull()) {
                        wpDto.setLongitude(wpNode.get("longitude").asDouble());
                    }
                    if (wpNode.has("state") && !wpNode.get("state").isNull()) {
                        wpDto.setState(wpNode.get("state").asText());
                    }
                    if (wpNode.has("arrivedAt") && !wpNode.get("arrivedAt").isNull()) {
                        wpDto.setArrivedAt(java.time.Instant.parse(wpNode.get("arrivedAt").asText()));
                    }
                    if (wpNode.has("remainingDistance") && !wpNode.get("remainingDistance").isNull()) {
                        wpDto.setRemainingDistance(wpNode.get("remainingDistance").asDouble());
                    }
                    if (wpNode.has("remainingTime") && !wpNode.get("remainingTime").isNull()) {
                        wpDto.setRemainingTime(wpNode.get("remainingTime").asDouble());
                    }
                    
                    waypointProgresses.add(wpDto);
                }
                tripDto.setWaypointProgresses(waypointProgresses);
            }

            // Parse currentLocation object
            JsonNode currentLocationNode = root.get("currentLocation");
            if (currentLocationNode != null && !currentLocationNode.isNull()) {
                NavigaTripUpdateEvent.CurrentLocationDto currentLocation = new NavigaTripUpdateEvent.CurrentLocationDto();
                
                if (currentLocationNode.has("carId") && !currentLocationNode.get("carId").isNull()) {
                    currentLocation.setCarId(currentLocationNode.get("carId").asText());
                }
                if (currentLocationNode.has("latitude") && !currentLocationNode.get("latitude").isNull()) {
                    currentLocation.setLatitude(currentLocationNode.get("latitude").asDouble());
                }
                if (currentLocationNode.has("longitude") && !currentLocationNode.get("longitude").isNull()) {
                    currentLocation.setLongitude(currentLocationNode.get("longitude").asDouble());
                }
                if (currentLocationNode.has("speed") && !currentLocationNode.get("speed").isNull()) {
                    currentLocation.setSpeed(currentLocationNode.get("speed").asDouble());
                }
                if (currentLocationNode.has("heading") && !currentLocationNode.get("heading").isNull()) {
                    currentLocation.setHeading(currentLocationNode.get("heading").asDouble());
                }
                if (currentLocationNode.has("timestamp") && !currentLocationNode.get("timestamp").isNull()) {
                    currentLocation.setTimestamp(java.time.Instant.parse(currentLocationNode.get("timestamp").asText()));
                }
                
                tripDto.setCurrentLocation(currentLocation);
            }

            return tripDto;

        } catch (Exception e) {
            logger.warn("⚠️ Failed to parse Naviga API response: {}", e.getMessage());
            return null;
        }
    }
}
