package com.gocavgo.Navigation.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.Trip;
import com.gocavgo.Navigation.model.dto.*;
import com.gocavgo.Navigation.model.enums.TripStatus;
import com.gocavgo.Navigation.service.NavigationService;
import com.gocavgo.Navigation.service.TripService;
import com.gocavgo.Navigation.store.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/gps")
@RequiredArgsConstructor
@Slf4j
public class GpsController {
    private final NavigationService navigationService;
    private final TripService tripService;
    private final TripRepository tripRepository;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<TripResponse> updateGps(@RequestBody List<GpsUpdateRequest> updates) {
        return processBatchGpsUpdates(updates);
    }

    /**
     * Process a batch of GPS updates
     */
    private ResponseEntity<TripResponse> processBatchGpsUpdates(List<GpsUpdateRequest> updates) {
        if (updates == null || updates.isEmpty()) {
            log.warn("Empty batch GPS update request");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        try {
            // All updates must have the same carId
            String carId = updates.get(0).getCarId();
            if (carId == null || carId.isEmpty()) {
                log.warn("Missing carId in batch GPS update");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }

            // Validate all updates have the same carId
            for (GpsUpdateRequest update : updates) {
                if (update.getCarId() == null || !update.getCarId().equals(carId)) {
                    log.warn("All GPS updates in batch must have the same carId");
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
                }
            }

            // Find active trip for this car
            Trip trip = tripRepository.findMostRecentByCarIdAndStatuses(
                    carId,
                    java.util.Arrays.asList(TripStatus.ACTIVE, TripStatus.CREATED)).orElse(null);

            if (trip == null) {
                log.warn("No active trip found for carId: {}", carId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            // Activate trip if it's in CREATED status
            if (trip.getStatus() == TripStatus.CREATED) {
                trip.setStatus(TripStatus.ACTIVE);
                tripRepository.save(trip);
            }

            // Get route from trip
            Route route = tripService.getRouteFromTrip(trip);

            // Get original trip waypoints for waypoint tracking
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints;
            try {
                originalWaypoints = tripService.getOriginalWaypoints(trip);
            } catch (Exception e) {
                log.error("Failed to get original waypoints from trip", e);
                originalWaypoints = new ArrayList<>();
            }

            // Process batch GPS updates
            NavigationService.NavigationResult result = navigationService.processBatchGpsUpdates(
                    carId,
                    trip.getId(),
                    updates,
                    route,
                    trip.isCityTrip(),
                    originalWaypoints,
                    trip.isIncludeOrigin());

            if (result == null) {
                // No valid updates were processed
                log.warn("No valid GPS updates processed in batch for carId: {}", carId);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
            }

            // Check for trip completion
            if (result.waypointProgresses != null && !result.waypointProgresses.isEmpty()) {
                if (isTripCompleted(result.waypointProgresses)) {
                    tripService.updateTripStatus(carId, TripStatus.COMPLETED);
                }
            }

            // Build current location using the last processed update
            // Find the last valid update to get speed/heading
            GpsUpdateRequest lastUpdate = updates.stream()
                    .filter(u -> u.getTimestamp() != null)
                    .max(Comparator.comparing(GpsUpdateRequest::getTimestamp))
                    .orElse(updates.get(updates.size() - 1));

            CurrentLocation currentLocation = CurrentLocation.builder()
                    .carId(carId)
                    .latitude(result.snappedLocation[0]) // Map-matched latitude
                    .longitude(result.snappedLocation[1]) // Map-matched longitude
                    .speed(lastUpdate.getSpeed())
                    .heading(lastUpdate.getHeading())
                    .timestamp(lastUpdate.getTimestamp() != null ? lastUpdate.getTimestamp() : Instant.now())
                    .build();

            // Get instructions if they were requested
            Instruction instructions = null; // TODO: Retrieve from cache if includeInstructions was true

            // Build response (render=false for GPS updates to avoid large payloads)
            TripResponse response = tripService.getTripResponse(
                    carId,
                    result.route,
                    result.waypointProgresses,
                    instructions,
                    currentLocation,
                    false // Don't include route data in GPS update responses
            );

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.error("Navigation state error", e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Error processing batch GPS updates", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Check if a trip is completed based on waypoint progress.
     * A trip is completed when:
     * - The final waypoint is either "ARRIVED" or "DONE"
     * - All previous waypoints are "DONE"
     */
    private boolean isTripCompleted(List<com.gocavgo.Navigation.model.WaypointProgress> waypointProgresses) {
        if (waypointProgresses == null || waypointProgresses.isEmpty()) {
            return false;
        }

        int totalWaypoints = waypointProgresses.size();

        // Check all waypoints except the last one are DONE
        for (int i = 0; i < totalWaypoints - 1; i++) {
            String state = waypointProgresses.get(i).getState().name();
            if (!"DONE".equals(state)) {
                return false;
            }
        }

        // Check the final waypoint is either ARRIVED or DONE
        String finalState = waypointProgresses.get(totalWaypoints - 1).getState().name();
        return "ARRIVED".equals(finalState) || "DONE".equals(finalState);
    }
}
