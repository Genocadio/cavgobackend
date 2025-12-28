package com.gocavgo.Navigation.scheduler;

import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.Trip;
import com.gocavgo.Navigation.model.WaypointProgress;
import com.gocavgo.Navigation.model.dto.CurrentLocation;
import com.gocavgo.Navigation.model.dto.Waypoint;
import com.gocavgo.Navigation.model.enums.TripStatus;
import com.gocavgo.Navigation.service.NavigationSnapshotService;
import com.gocavgo.Navigation.service.TripService;
import com.gocavgo.Navigation.store.RedisStateStore;
import com.gocavgo.Navigation.store.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class NavigationStateScheduler {
    private final TripRepository tripRepository;
    private final RedisStateStore redisStateStore;
    private final TripService tripService;
    private final NavigationSnapshotService snapshotService;
    
    @Value("${navigation.persistence.periodic-save-interval-seconds:10}")
    private long periodicSaveIntervalSeconds;
    
    /**
     * Periodically save navigation state for all active trips
     * Runs every 10 seconds by default (configurable)
     */
    @Scheduled(fixedRateString = "${navigation.persistence.periodic-save-interval-seconds:10}000")
    public void saveActiveTripsPeriodically() {
        try {
            // Get all active trips (ACTIVE and CREATED status)
            List<Trip> activeTripsList = new java.util.ArrayList<>();
            activeTripsList.addAll(tripRepository.findByStatus(TripStatus.ACTIVE));
            activeTripsList.addAll(tripRepository.findByStatus(TripStatus.CREATED));
            
            if (activeTripsList.isEmpty()) {
                return; // No active trips
            }
            
            int savedCount = 0;
            int skippedCount = 0;
            
            for (Trip trip : activeTripsList) {
                try {
                    // Load navigation state from Redis
                    NavigationState state = redisStateStore.getNavigationState(trip.getCarId());
                    if (state == null) {
                        skippedCount++;
                        continue; // No state in Redis
                    }
                    
                    // Only process if state belongs to this trip (prevent cross-trip contamination)
                    if (state.getTripId() == null || !state.getTripId().equals(trip.getId())) {
                        skippedCount++;
                        log.debug("Skipping periodic save for trip {} - state belongs to different trip (state.tripId: {})",
                                trip.getId(), state.getTripId());
                        continue;
                    }
                    
                    // Load route and waypoints
                    Route route = tripService.getRouteFromTrip(trip);
                    List<Waypoint> originalWaypoints = tripService.getOriginalWaypoints(trip);
                    
                    // Rebuild waypoint progresses from state (using package-private method)
                    // We need to access it via reflection or make it public, for now use a workaround
                    // Actually, since it's package-private and we're in the same package structure, we can access it
                    List<WaypointProgress> waypointProgresses = tripService.buildWaypointProgressesFromState(
                            route, state, originalWaypoints, trip.isIncludeOrigin());
                    
                    // Build current location from state
                    CurrentLocation currentLocation = null;
                    if (state.getCurrentLatitude() != null && state.getCurrentLongitude() != null) {
                        currentLocation = CurrentLocation.builder()
                                .carId(trip.getCarId())
                                .latitude(state.getCurrentLatitude())
                                .longitude(state.getCurrentLongitude())
                                .speed(state.getCurrentSpeed() != null ? state.getCurrentSpeed() : 0.0)
                                .heading(state.getCurrentHeading())
                                .timestamp(state.getCurrentLocationTimestamp() != null ? 
                                        state.getCurrentLocationTimestamp() : Instant.now())
                                .build();
                    }
                    
                    // Save snapshot
                    snapshotService.saveSnapshot(trip.getCarId(), trip.getId(), state, route, 
                            waypointProgresses, currentLocation);
                    savedCount++;
                    
                } catch (Exception e) {
                    log.warn("Failed to save periodic snapshot for trip {}: {}", trip.getId(), e.getMessage());
                    skippedCount++;
                }
            }
            
            if (savedCount > 0) {
                log.debug("Periodic snapshot saved: {} trips saved, {} skipped", savedCount, skippedCount);
            }
            
        } catch (Exception e) {
            log.error("Error in periodic navigation state save", e);
        }
    }
}

