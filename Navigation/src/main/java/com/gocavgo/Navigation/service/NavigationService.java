package com.gocavgo.Navigation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.WaypointProgress;
import com.gocavgo.Navigation.model.enums.WaypointState;
import com.gocavgo.Navigation.store.RedisStateStore;
import com.gocavgo.Navigation.util.GeoMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationService {
    private final RedisStateStore redisStateStore;
    private final EtaService etaService;
    private final RerouteService rerouteService;
    private final NavigationSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    @Value("${navigation.arrival.radius-meters:20}")
    private double arrivalRadius;

    @Value("${navigation.arrival.pass-threshold-meters:10}")
    private double passThreshold;

    @Value("${navigation.gps.max-age-seconds:30}")
    private long maxGpsAgeSeconds;

    @Value("${navigation.gps.validate-age:true}")
    private boolean validateGpsAge;

    @Value("${navigation.gps.min-accuracy-meters:100}")
    private double minAccuracy;

    @Value("${navigation.gps.map-matching.enabled:true}")
    private boolean mapMatchingEnabled;

    /**
     * Process GPS update and update navigation state
     */
    public NavigationResult processGpsUpdate(String carId, Long tripId, double gpsLat, double gpsLon,
            double speed, Double heading, Instant timestamp,
            Route route, boolean isCityTrip,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints,
            boolean includeOrigin) {
        // Get current navigation state
        NavigationState state = redisStateStore.getNavigationState(carId);
        if (state == null) {
            // Rebuild a fresh state if Redis entry expired or is missing
            log.warn("Navigation state missing for carId: {}, rebuilding for tripId: {}", carId, tripId);
            state = createFreshNavigationState(tripId);
            redisStateStore.saveNavigationState(carId, state);
        } else if (state.getTripId() == null || !state.getTripId().equals(tripId)) {
            // Reset leaked/cross-trip state to avoid rejecting valid GPS updates
            log.warn(
                    "Navigation state for carId {} belongs to different trip (state.tripId: {}, current tripId: {}). Resetting state.",
                    carId, state.getTripId(), tripId);
            state = createFreshNavigationState(tripId);
            redisStateStore.saveNavigationState(carId, state);
        }

        // Validate GPS timestamp (reject older than last processed)
        if (state.getLastUpdateTime() != null && timestamp.isBefore(state.getLastUpdateTime())) {
            long diffSeconds = Duration.between(timestamp, state.getLastUpdateTime()).getSeconds();
            log.warn("Rejecting GPS update with older timestamp for carId: {}, last: {}, new: {}, diff={}s",
                carId, state.getLastUpdateTime(), timestamp, diffSeconds);
            throw new IllegalArgumentException("OUT_OF_ORDER_TIMESTAMP: carId=" + carId +
                ", tripId=" + tripId + ", last=" + state.getLastUpdateTime() +
                ", new=" + timestamp + ", diffSeconds=" + diffSeconds);
        }

        // Validate GPS age (only if validation is enabled)
        if (validateGpsAge) {
            long now = Instant.now().getEpochSecond();
            long ageSeconds = now - timestamp.getEpochSecond();
            if (ageSeconds > maxGpsAgeSeconds) {
                log.warn("Rejecting GPS update too old for carId: {}, age: {}s (max={}s)", carId, ageSeconds,
                        maxGpsAgeSeconds);
                throw new IllegalArgumentException("GPS_TOO_OLD: carId=" + carId + ", tripId=" + tripId +
                        ", ageSeconds=" + ageSeconds + ", maxAgeSeconds=" + maxGpsAgeSeconds +
                        ", ts=" + timestamp + ", nowEpoch=" + now);
            }
        }

        // Snap GPS to route (always needed for internal progress tracking/distance
        // calculation)
        GeoMath.SnapResult snapResult = GeoMath.snapToRoute(gpsLat, gpsLon, route, state.getLastSnappedIndex());

        // Calculate the exact snapped location on the route
        double[] snappedLocation = calculateSnappedLocation(route, snapResult);

        // Determine displayed location based on configuration
        // If map matching is disabled, use raw GPS. If enabled, use snapped location.
        double[] displayLocation = mapMatchingEnabled ? snappedLocation : new double[] { gpsLat, gpsLon };

        // Store previous waypoint progresses to detect state changes
        List<WaypointProgress> previousProgresses = getPreviousWaypointProgresses(state, route, originalWaypoints,
                includeOrigin);

        // Update navigation state
        state.setLastSnappedIndex(snapResult.index);
        state.setDistanceTravelled(snapResult.totalDistance);
        state.setLastUpdateTime(timestamp);
        state.setTripId(tripId); // Ensure tripId is set (in case it wasn't initialized)

        // Update current location in state (Snapped or Raw based on config)
        state.setCurrentLatitude(displayLocation[0]);
        state.setCurrentLongitude(displayLocation[1]);
        state.setCurrentSpeed(speed);
        state.setCurrentHeading(heading);
        state.setCurrentLocationTimestamp(timestamp);

        // Update average speed (simple moving average)
        updateAverageSpeed(state, speed);

        // Check for leg completion (advance leg index if we passed the stop)
        checkLegCompletion(route, state);

        // Check for off-route and reroute if needed
        boolean reroutingOccurred = false;
        double deviationDistance = snapResult.distanceFromRoute; // Capture before potential re-snap
        List<double[]> remainingWaypoints = getRemainingWaypoints(route, state);

        // FIX: If includeOrigin is false and we are on the first leg, ensure Origin is
        // NOT in remaining waypoints
        // This prevents rerouting back to start (G->A->B) instead of forward (G->B)
        if (!includeOrigin && state.getCurrentLegIndex() == 0 && !remainingWaypoints.isEmpty()
                && !originalWaypoints.isEmpty()) {
            double[] firstRemaining = remainingWaypoints.get(0);
            com.gocavgo.Navigation.model.dto.Waypoint origin = originalWaypoints.get(0);

            // Check if first remaining point is effectively the Origin (< 5m distance)
            double distToOrigin = GeoMath.haversineDistance(firstRemaining[0], firstRemaining[1],
                    origin.getLatitude(), origin.getLongitude());

            if (distToOrigin < 5.0) {
                log.info("Removing Origin from reroute waypoints (includeOrigin=false, dist={}m)", distToOrigin);
                remainingWaypoints.remove(0);
            }
        }

        Route updatedRoute = rerouteService.checkAndReroute(carId, tripId, gpsLat, gpsLon, route, state,
                isCityTrip, remainingWaypoints);
        if (updatedRoute != null) {
            route = updatedRoute; // Use new route
            reroutingOccurred = true;
            // Re-snap after reroute
            snapResult = GeoMath.snapToRoute(gpsLat, gpsLon, route, 0);
            snappedLocation = calculateSnappedLocation(route, snapResult);

            // Recalculate display location for new route
            // If map matching enabled, snap to new route. If disabled, keep raw GPS.
            displayLocation = mapMatchingEnabled ? snappedLocation : new double[] { gpsLat, gpsLon };

            // Update current location again after reroute
            state.setCurrentLatitude(displayLocation[0]);
            state.setCurrentLongitude(displayLocation[1]);
        }

        // Update waypoint progress based on original trip waypoints
        List<WaypointProgress> waypointProgresses = updateWaypointProgress(
                route, state, originalWaypoints, includeOrigin);

        // Log detailed reroute snapshot if occurred
        if (reroutingOccurred) {
            logRerouteDetails(carId, deviationDistance, previousProgresses, waypointProgresses, remainingWaypoints,
                    originalWaypoints);
        }

        // Calculate ETA
        double eta = etaService.calculateETA(route, state, speed);

        // Save updated state to Redis
        redisStateStore.saveNavigationState(carId, state);

        // Check if immediate save is needed (state change or rerouting)
        boolean shouldSaveImmediately = reroutingOccurred ||
                snapshotService.shouldSaveSnapshotImmediately(previousProgresses, waypointProgresses);

        if (shouldSaveImmediately && tripId != null) {
            // Build current location DTO
            com.gocavgo.Navigation.model.dto.CurrentLocation currentLocation = com.gocavgo.Navigation.model.dto.CurrentLocation
                    .builder()
                    .carId(carId)
                    .latitude(displayLocation[0])
                    .longitude(displayLocation[1])
                    .speed(speed)
                    .heading(heading)
                    .timestamp(timestamp)
                    .build();

            // Save snapshot immediately
            snapshotService.saveSnapshot(carId, tripId, state, route, waypointProgresses, currentLocation);
            log.debug("Immediate snapshot saved for tripId: {}, carId: {} (rerouting: {}, state change: {})",
                    tripId, carId, reroutingOccurred, !reroutingOccurred);
        }

        return new NavigationResult(state, route, waypointProgresses, eta, displayLocation);
    }

    private NavigationState createFreshNavigationState(Long tripId) {
        return NavigationState.builder()
                .lastSnappedIndex(0)
                .distanceTravelled(0.0)
                .currentLegIndex(0)
                .lastUpdateTime(null)
                .avgSpeed(0.0)
                .offRouteConsecutiveCount(0)
                .waypointStatesJson("{}")
                .tripId(tripId)
                .build();
    }

    /**
     * Process a batch of GPS updates sequentially
     * Updates are sorted by timestamp and processed in order
     * Invalid updates are skipped (logged) and processing continues
     * If rerouting occurs, subsequent updates use the new route
     */
    public NavigationResult processBatchGpsUpdates(String carId, Long tripId,
            List<com.gocavgo.Navigation.model.dto.GpsUpdateRequest> updates,
            Route initialRoute,
            boolean isCityTrip,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints,
            boolean includeOrigin) {
        if (updates == null || updates.isEmpty()) {
            log.warn("Empty batch GPS updates for carId: {}", carId);
            return null;
        }

        // Sort updates by timestamp (ascending) to ensure chronological processing
        List<com.gocavgo.Navigation.model.dto.GpsUpdateRequest> sortedUpdates = new ArrayList<>(updates);
        sortedUpdates.sort(Comparator.comparing(
                u -> u.getTimestamp() != null ? u.getTimestamp() : Instant.MIN,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Route currentRoute = initialRoute;
        NavigationResult lastResult = null;
        int processedCount = 0;
        int skippedCount = 0;

        log.debug("Processing batch of {} GPS updates for carId: {}", sortedUpdates.size(), carId);

        for (com.gocavgo.Navigation.model.dto.GpsUpdateRequest update : sortedUpdates) {
            // Set default timestamp if missing
            Instant timestamp = update.getTimestamp() != null ? update.getTimestamp() : Instant.now();

            try {
                // Process GPS update with current route
                NavigationResult result = processGpsUpdate(
                        carId,
                        tripId,
                        update.getLatitude(),
                        update.getLongitude(),
                        update.getSpeed(),
                        update.getHeading(),
                        timestamp,
                        currentRoute,
                        isCityTrip,
                        originalWaypoints,
                        includeOrigin);

                if (result != null) {
                    // Update succeeded
                    processedCount++;

                    // If reroute occurred, update currentRoute for subsequent updates
                    if (result.route != currentRoute) {
                        log.info(
                                "Reroute occurred during batch processing for carId: {}, updating route for subsequent updates",
                                carId);
                        currentRoute = result.route;
                    }

                    lastResult = result;
                } else {
                    // Update was rejected (out of order, too old, etc.)
                    skippedCount++;
                    log.debug("Skipped invalid GPS update in batch for carId: {} at timestamp: {}", carId, timestamp);
                }
            } catch (Exception e) {
                // Skip this update and continue with next
                skippedCount++;
                log.warn("Error processing GPS update in batch for carId: {} at timestamp: {}, skipping: {}",
                        carId, timestamp, e.getMessage());
            }
        }

        log.info("Batch GPS processing completed for carId: {}, processed: {}, skipped: {}",
                carId, processedCount, skippedCount);

        return lastResult; // Return result from last successfully processed update
    }

    /**
     * Update average speed using exponential moving average
     */
    private void updateAverageSpeed(NavigationState state, double currentSpeed) {
        double alpha = 0.3; // Smoothing factor
        if (state.getAvgSpeed() == 0) {
            state.setAvgSpeed(currentSpeed);
        } else {
            state.setAvgSpeed(alpha * currentSpeed + (1 - alpha) * state.getAvgSpeed());
        }
    }

    /**
     * Update waypoint progress based on original trip waypoints
     * This ensures consistent waypoint tracking even after rerouting
     * Maintains monotonic progress - once a waypoint is DONE, it stays DONE
     */
    private List<WaypointProgress> updateWaypointProgress(Route route, NavigationState state,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints,
            boolean includeOrigin) {
        List<WaypointProgress> progresses = new ArrayList<>();

        // Load previous waypoint states from stored JSON
        Map<Integer, WaypointState> previousStates = loadWaypointStates(state.getWaypointStatesJson());
        Map<Integer, Instant> previousArrivedAt = loadWaypointArrivedAt(state.getWaypointStatesJson());
        Map<Integer, WaypointState> newStates = new HashMap<>();

        // Determine which waypoints to track based on includeOrigin
        int startIndex = includeOrigin ? 0 : 1;

        // Track progress against original trip waypoints, not route waypoints
        // This ensures consistency even after rerouting
        for (int i = startIndex; i < originalWaypoints.size(); i++) {
            com.gocavgo.Navigation.model.dto.Waypoint originalWaypoint = originalWaypoints.get(i);

            // Find closest point on current route to this original waypoint
            double minDistance = Double.MAX_VALUE;
            double waypointDistance = 0.0;
            int closestRouteIndex = -1;

            List<double[]> polyline = route.getPolyline();
            List<Double> cumulativeDistances = route.getCumulativeDistances();

            // Find closest point on route polyline to original waypoint
            for (int j = 0; j < polyline.size(); j++) {
                double[] routePoint = polyline.get(j);
                double dist = GeoMath.haversineDistance(
                        originalWaypoint.getLatitude(), originalWaypoint.getLongitude(),
                        routePoint[0], routePoint[1]);

                if (dist < minDistance) {
                    minDistance = dist;
                    closestRouteIndex = j;
                    waypointDistance = cumulativeDistances.get(j);
                }
            }

            if (closestRouteIndex < 0) {
                continue; // Skip if couldn't find route point
            }

            double remainingDistance = waypointDistance - state.getDistanceTravelled();
            double remainingTime = remainingDistance > 0 ? remainingDistance / Math.max(state.getAvgSpeed(), 0.5) : 0.0;

            // Get previous state for this waypoint (use original waypoint index)
            WaypointState previousState = previousStates.getOrDefault(i, WaypointState.APPROACHING);
            WaypointState waypointState;
            Instant arrivedAt = previousArrivedAt.get(i);

            // Enforce monotonic progress: APPROACHING → ARRIVED → DONE (no backward
            // transitions)
            if (previousState == WaypointState.DONE) {
                // Once DONE, always DONE
                waypointState = WaypointState.DONE;
            } else if (previousState == WaypointState.ARRIVED) {
                // If previously ARRIVED, check if we've passed through
                if (remainingDistance <= -passThreshold) {
                    waypointState = WaypointState.DONE;
                } else {
                    waypointState = WaypointState.ARRIVED; // Stay ARRIVED
                }
            } else {
                // Previously APPROACHING - check if we've arrived or passed
                if (remainingDistance <= -passThreshold) {
                    // Passed through (clearly past waypoint)
                    waypointState = WaypointState.DONE;
                    arrivedAt = Instant.now();
                } else if (remainingDistance <= arrivalRadius || remainingDistance <= 0) {
                    // At or near waypoint
                    waypointState = WaypointState.ARRIVED;
                    if (arrivedAt == null) {
                        arrivedAt = Instant.now();
                    }
                } else {
                    // Still approaching
                    waypointState = WaypointState.APPROACHING;
                }
            }

            newStates.put(i, waypointState);

            // Update arrivedAt map
            Map<Integer, Instant> updatedArrivedAt = new HashMap<>(previousArrivedAt);
            if (arrivedAt != null) {
                updatedArrivedAt.put(i, arrivedAt);
            }

            progresses.add(WaypointProgress.builder()
                    .waypointIndex(i)
                    .waypointId(originalWaypoint.getId())
                    .waypointName(originalWaypoint.getName())
                    .latitude(originalWaypoint.getLatitude())
                    .longitude(originalWaypoint.getLongitude())
                    .state(waypointState)
                    .arrivedAt(arrivedAt)
                    .remainingDistance(Math.max(0, remainingDistance))
                    .remainingTime(remainingTime)
                    .build());
        }

        // Save updated waypoint states
        Map<Integer, Instant> finalArrivedAt = new HashMap<>();
        for (WaypointProgress wp : progresses) {
            if (wp.getArrivedAt() != null) {
                finalArrivedAt.put(wp.getWaypointIndex(), wp.getArrivedAt());
            }
        }
        state.setWaypointStatesJson(saveWaypointStates(newStates, finalArrivedAt));

        return progresses;
    }

    /**
     * Load waypoint states from JSON
     */
    private Map<Integer, WaypointState> loadWaypointStates(String waypointStatesJson) {
        Map<Integer, WaypointState> states = new HashMap<>();
        if (waypointStatesJson == null || waypointStatesJson.isEmpty() || waypointStatesJson.equals("{}")) {
            return states;
        }

        try {
            Map<String, String> jsonMap = objectMapper.readValue(waypointStatesJson,
                    new TypeReference<Map<String, String>>() {
                    });
            for (Map.Entry<String, String> entry : jsonMap.entrySet()) {
                if (entry.getKey().startsWith("state_")) {
                    int waypointIndex = Integer.parseInt(entry.getKey().substring(6));
                    WaypointState state = WaypointState.valueOf(entry.getValue());
                    states.put(waypointIndex, state);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse waypoint states JSON: {}", waypointStatesJson, e);
        }

        return states;
    }

    /**
     * Load waypoint arrivedAt timestamps from JSON
     */
    private Map<Integer, Instant> loadWaypointArrivedAt(String waypointStatesJson) {
        Map<Integer, Instant> arrivedAtMap = new HashMap<>();
        if (waypointStatesJson == null || waypointStatesJson.isEmpty() || waypointStatesJson.equals("{}")) {
            return arrivedAtMap;
        }

        try {
            Map<String, String> jsonMap = objectMapper.readValue(waypointStatesJson,
                    new TypeReference<Map<String, String>>() {
                    });
            for (Map.Entry<String, String> entry : jsonMap.entrySet()) {
                if (entry.getKey().startsWith("arrivedAt_")) {
                    int waypointIndex = Integer.parseInt(entry.getKey().substring(10));
                    Instant arrivedAt = Instant.parse(entry.getValue());
                    arrivedAtMap.put(waypointIndex, arrivedAt);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse waypoint arrivedAt from JSON: {}", waypointStatesJson, e);
        }

        return arrivedAtMap;
    }

    /**
     * Save waypoint states to JSON
     */
    private String saveWaypointStates(Map<Integer, WaypointState> states, Map<Integer, Instant> arrivedAt) {
        Map<String, String> jsonMap = new HashMap<>();
        for (Map.Entry<Integer, WaypointState> entry : states.entrySet()) {
            jsonMap.put("state_" + entry.getKey(), entry.getValue().name());
        }
        for (Map.Entry<Integer, Instant> entry : arrivedAt.entrySet()) {
            if (entry.getValue() != null) {
                jsonMap.put("arrivedAt_" + entry.getKey(), entry.getValue().toString());
            }
        }

        try {
            return objectMapper.writeValueAsString(jsonMap);
        } catch (Exception e) {
            log.error("Failed to serialize waypoint states to JSON", e);
            return "{}";
        }
    }

    /**
     * Get remaining waypoints from current position
     */
    private List<double[]> getRemainingWaypoints(Route route, NavigationState state) {
        List<double[]> polyline = route.getPolyline();
        List<Integer> legStopIndices = route.getLegStopIndices();
        List<double[]> remaining = new ArrayList<>();

        int currentLeg = state.getCurrentLegIndex();
        if (currentLeg < legStopIndices.size() - 1) {
            for (int i = currentLeg + 1; i < legStopIndices.size(); i++) {
                int index = legStopIndices.get(i);
                if (index < polyline.size()) {
                    remaining.add(polyline.get(index));
                }
            }
        }

        return remaining;
    }

    /**
     * Check if current leg is completed and advance leg index
     */
    private void checkLegCompletion(Route route, NavigationState state) {
        List<Integer> legStopIndices = route.getLegStopIndices();
        int currentLeg = state.getCurrentLegIndex();

        // If no more legs or invalid state
        if (legStopIndices == null || currentLeg >= legStopIndices.size() - 1) {
            return;
        }

        // Get the polyline index where the current leg ends
        int targetIndex = legStopIndices.get(currentLeg + 1);

        // If we passed the target index on the polyline, advance leg
        if (state.getLastSnappedIndex() >= targetIndex) {
            log.debug("Leg {} completed. Passed target index {} (current snap: {})",
                    currentLeg, targetIndex, state.getLastSnappedIndex());
            state.setCurrentLegIndex(currentLeg + 1);
        }
    }

    /**
     * Calculate the exact snapped location on the route segment
     */
    private double[] calculateSnappedLocation(Route route, GeoMath.SnapResult snapResult) {
        List<double[]> polyline = route.getPolyline();
        int index = snapResult.index;

        // Ensure index is within bounds
        if (index < 0) {
            index = 0;
        }
        if (index >= polyline.size()) {
            index = polyline.size() - 1;
        }

        if (index >= polyline.size() - 1) {
            // At the end of route, return the last point
            return polyline.get(polyline.size() - 1);
        }

        double[] segStart = polyline.get(index);
        double[] segEnd = polyline.get(index + 1);

        // Interpolate along the segment based on offset
        double segDist = GeoMath.haversineDistance(segStart[0], segStart[1], segEnd[0], segEnd[1]);
        if (segDist == 0) {
            return segStart;
        }

        double t = snapResult.offset / segDist;
        t = Math.max(0, Math.min(1, t)); // Clamp to [0, 1]

        // Linear interpolation
        double snappedLat = segStart[0] + t * (segEnd[0] - segStart[0]);
        double snappedLon = segStart[1] + t * (segEnd[1] - segStart[1]);

        return new double[] { snappedLat, snappedLon };
    }

    /**
     * Get previous waypoint progresses from saved state (for comparison)
     */
    private List<WaypointProgress> getPreviousWaypointProgresses(NavigationState state, Route route,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints,
            boolean includeOrigin) {
        // Load previous states from JSON
        Map<Integer, WaypointState> previousStates = loadWaypointStates(state.getWaypointStatesJson());
        Map<Integer, Instant> previousArrivedAt = loadWaypointArrivedAt(state.getWaypointStatesJson());

        List<WaypointProgress> previousProgresses = new ArrayList<>();
        int startIndex = includeOrigin ? 0 : 1;

        for (int i = startIndex; i < originalWaypoints.size(); i++) {
            com.gocavgo.Navigation.model.dto.Waypoint originalWaypoint = originalWaypoints.get(i);
            WaypointState previousState = previousStates.getOrDefault(i, WaypointState.APPROACHING);
            Instant arrivedAt = previousArrivedAt.get(i);

            // Find closest point on route to waypoint
            double minDistance = Double.MAX_VALUE;
            double waypointDistance = 0.0;
            List<double[]> polyline = route.getPolyline();
            List<Double> cumulativeDistances = route.getCumulativeDistances();

            for (int j = 0; j < polyline.size(); j++) {
                double[] routePoint = polyline.get(j);
                double dist = GeoMath.haversineDistance(
                        originalWaypoint.getLatitude(), originalWaypoint.getLongitude(),
                        routePoint[0], routePoint[1]);

                if (dist < minDistance) {
                    minDistance = dist;
                    waypointDistance = cumulativeDistances.get(j);
                }
            }

            double remainingDistance = waypointDistance - state.getDistanceTravelled();
            double remainingTime = remainingDistance > 0 ? remainingDistance / Math.max(state.getAvgSpeed(), 0.5) : 0.0;

            previousProgresses.add(WaypointProgress.builder()
                    .waypointIndex(i)
                    .waypointId(originalWaypoint.getId())
                    .waypointName(originalWaypoint.getName())
                    .latitude(originalWaypoint.getLatitude())
                    .longitude(originalWaypoint.getLongitude())
                    .state(previousState)
                    .arrivedAt(arrivedAt)
                    .remainingDistance(Math.max(0, remainingDistance))
                    .remainingTime(remainingTime)
                    .build());
        }

        return previousProgresses;
    }

    /**
     * Log detailed snapshot of reroute event
     */
    private void logRerouteDetails(String carId,
            double deviationDistance,
            List<WaypointProgress> preRerouteProgress,
            List<WaypointProgress> postRerouteProgress,
            List<double[]> rerouteWaypoints,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n======================================================\n");
        sb.append("🚨 REROUTE EVENT DETECTED for Car: ").append(carId).append("\n");
        sb.append("======================================================\n");

        // 1. Deviation Context
        sb.append(String.format("Off-Route Distance: %.2fm\n", deviationDistance));

        // 2. Pre-Reroute Status
        sb.append("\n[PRE-REROUTE SNAPSHOT]\n");
        logProgressSnapshot(sb, preRerouteProgress);

        // 3. Reroute Targets
        sb.append("\n[REROUTE CALCULATION]\n");
        sb.append("Routing from Current Location -> ");
        if (rerouteWaypoints.isEmpty()) {
            sb.append("(End of Route)");
        } else {
            for (int i = 0; i < rerouteWaypoints.size(); i++) {
                double[] wp = rerouteWaypoints.get(i);
                String name = findWaypointName(wp, originalWaypoints);
                sb.append(name);
                if (i < rerouteWaypoints.size() - 1)
                    sb.append(" -> ");
            }
        }
        sb.append("\n");

        // 4. Post-Reroute Status
        sb.append("\n[POST-REROUTE SNAPSHOT]\n");
        logProgressSnapshot(sb, postRerouteProgress);
        sb.append("======================================================\n");

        log.info(sb.toString());
    }

    private void logProgressSnapshot(StringBuilder sb, List<WaypointProgress> progresses) {
        if (progresses == null || progresses.isEmpty()) {
            sb.append("  (No waypoint progress available)\n");
            return;
        }
        for (WaypointProgress wp : progresses) {
            sb.append(String.format("  • %s: %s (Remaining: %.1fm)\n",
                    wp.getWaypointName(), wp.getState(), wp.getRemainingDistance()));
        }
    }

    private String findWaypointName(double[] coords,
            List<com.gocavgo.Navigation.model.dto.Waypoint> originalWaypoints) {
        for (com.gocavgo.Navigation.model.dto.Waypoint wp : originalWaypoints) {
            if (GeoMath.haversineDistance(coords[0], coords[1], wp.getLatitude(), wp.getLongitude()) < 10.0) {
                return wp.getName();
            }
        }
        return String.format("WP(%.5f,%.5f)", coords[0], coords[1]);
    }

    /**
     * Result of GPS processing
     */
    public static class NavigationResult {
        public final NavigationState state;
        public final Route route;
        public final List<WaypointProgress> waypointProgresses;
        public final double eta;
        public final double[] snappedLocation; // [lat, lon] of map-matched position

        public NavigationResult(NavigationState state, Route route,
                List<WaypointProgress> waypointProgresses, double eta, double[] snappedLocation) {
            this.state = state;
            this.route = route;
            this.waypointProgresses = waypointProgresses;
            this.eta = eta;
            this.snappedLocation = snappedLocation;
        }
    }
}
