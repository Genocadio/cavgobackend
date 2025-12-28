package com.gocavgo.Navigation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.NavigationSnapshot;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.Trip;
import com.gocavgo.Navigation.model.WaypointProgress;
import com.gocavgo.Navigation.model.dto.*;
import com.gocavgo.Navigation.model.enums.TripStatus;
import com.gocavgo.Navigation.model.enums.WaypointState;
import com.gocavgo.Navigation.routing.OsrmClient;
import com.gocavgo.Navigation.store.NavigationSnapshotRepository;
import com.gocavgo.Navigation.store.RedisStateStore;
import com.gocavgo.Navigation.store.TripRepository;
import com.gocavgo.Navigation.service.TripEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {
    private final TripRepository tripRepository;
    private final RedisStateStore redisStateStore;
    private final NavigationSnapshotRepository snapshotRepository;
    private final NavigationSnapshotService snapshotService;
    private final OsrmClient osrmClient;
    private final TripEventPublisher tripEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Create a new trip
     * If there's an existing active/created trip for this car, cancel it first
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Create a new trip
     * If there's an existing active/created trip for this car, cancel it first
     */
    @Transactional
    public TripResponse createTrip(TripCreateRequest request) {
        if (request.getWaypoints() == null || request.getWaypoints().isEmpty()) {
            throw new IllegalArgumentException("Waypoints cannot be empty");
        }
        // PURGE: Delete all previous trips for this car (any status), their snapshots, and Redis state
        List<Trip> existingTrips = tripRepository.findByCarId(request.getCarId());
        for (Trip existingTrip : existingTrips) {
            try {
                org.springframework.data.domain.Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE);
                org.springframework.data.domain.Page<NavigationSnapshot> snapshots =
                        snapshotRepository.findByTripIdOrderBySnapshotTimestampDesc(existingTrip.getId(), pageable);
                if (!snapshots.isEmpty()) {
                    snapshotRepository.deleteAll(snapshots.getContent());
                    log.info("Deleted {} snapshots for previous trip {} (carId: {})",
                            snapshots.getNumberOfElements(), existingTrip.getId(), request.getCarId());
                }
                tripRepository.delete(existingTrip);
                log.info("Deleted previous trip {} for carId: {}", existingTrip.getId(), request.getCarId());
            } catch (Exception e) {
                log.error("Error purging previous trip {} for carId {}: {}", existingTrip.getId(), request.getCarId(), e.getMessage(), e);
            }
        }
        // Always clear Redis navigation state for this car to ensure fresh start
        redisStateStore.deleteNavigationState(request.getCarId());
        log.info("Cleared Redis navigation state for carId: {} prior to creating new trip", request.getCarId());

        // Convert waypoints to double[][] format for OSRM
        List<double[]> waypointCoords = request.getWaypoints().stream()
                .map(wp -> new double[] { wp.getLatitude(), wp.getLongitude() })
                .collect(Collectors.toList());

        // Handle includeOrigin logic
        List<double[]> routeWaypoints = new ArrayList<>(waypointCoords);
        // If includeOrigin=false, we still include origin in route but assume device is
        // there
        // If includeOrigin=true, device must travel to origin first

        // Call OSRM to get route
        Route route = osrmClient.getRoute(routeWaypoints, request.getIncludeInstructions());

        // Get instructions if requested
        Instruction instructions = null;
        if (request.getIncludeInstructions()) {
            instructions = osrmClient.getInstructions(routeWaypoints);
        }

        // Save route as JSON
        String routeJson;
        try {
            routeJson = objectMapper.writeValueAsString(route);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize route", e);
        }

        // Save waypoints as JSON
        String waypointsJson;
        try {
            waypointsJson = objectMapper.writeValueAsString(request.getWaypoints());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize waypoints", e);
        }

        Trip trip;
        if (request.getId() != null) {
            // Manual ID: Use native query to force INSERT and bypass Hibernate
            // merge/persist checks
            if (tripRepository.existsById(request.getId())) {
                throw new IllegalArgumentException("Trip with ID " + request.getId() + " already exists");
            }

            log.info("Creating trip with Manual ID: {}", request.getId());
            entityManager.createNativeQuery(
                    "INSERT INTO trips (id, car_id, status, waypoints_json, route_json, include_origin, is_city_trip, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .setParameter(1, request.getId())
                    .setParameter(2, request.getCarId())
                    .setParameter(3, TripStatus.CREATED.name())
                    .setParameter(4, waypointsJson)
                    .setParameter(5, routeJson)
                    .setParameter(6, request.getIncludeOrigin())
                    .setParameter(7, request.getIsCityTrip())
                    .setParameter(8, Instant.now())
                    .executeUpdate();

            // Load the newly created trip
            trip = tripRepository.findById(request.getId()).orElseThrow();
        } else {
            // Auto ID: Standard save
            Trip.TripBuilder tripBuilder = Trip.builder()
                    .carId(request.getCarId())
                    .status(TripStatus.CREATED)
                    .waypointsJson(waypointsJson)
                    .routeJson(routeJson)
                    .includeOrigin(request.getIncludeOrigin())
                    .isCityTrip(request.getIsCityTrip())
                    .createdAt(Instant.now());

            trip = tripBuilder.build();
            trip = tripRepository.save(trip);
        }

        // Clear any existing navigation state for this car (from previous trips)
        redisStateStore.deleteNavigationState(request.getCarId());

        // Initialize navigation state in Redis with tripId to prevent cross-trip
        // contamination
        NavigationState navState = NavigationState.builder()
                .lastSnappedIndex(0)
                .distanceTravelled(0.0)
                .currentLegIndex(0)
                .lastUpdateTime(null)
                .avgSpeed(0.0)
                .offRouteConsecutiveCount(0)
                .waypointStatesJson("{}") // Initialize empty waypoint states
                .tripId(trip.getId()) // Associate state with this specific trip
                .build();

        redisStateStore.saveNavigationState(request.getCarId(), navState);

        // Build waypoint progresses
        List<WaypointProgress> waypointProgresses = buildInitialWaypointProgresses(
                route, request.getWaypoints(), request.getIncludeOrigin());

        // Convert to DTOs (include route if instructions were requested, as that
        // implies rendering)
        boolean render = request.getIncludeInstructions();
        TripDto tripDto = convertToTripDto(trip, route, waypointProgresses, render, instructions);

        return TripResponse.builder()
                .trip(tripDto)
                .instructions(instructions)
                .currentLocation(null) // No current location on creation
                .build();
    }

    /**
     * Get trip response for a car
     * 
     * @param render If true, includes route polyline and instructions (if
     *               available)
     */
    public TripResponse getTripResponse(String carId, Route route,
            List<WaypointProgress> waypointProgresses,
            Instruction instructions,
            CurrentLocation currentLocation,
            boolean render) {
        Trip trip = tripRepository.findMostRecentByCarIdAndStatuses(
                carId,
                java.util.Arrays.asList(TripStatus.ACTIVE, TripStatus.CREATED))
                .orElseThrow(() -> new IllegalStateException("No active trip found for carId: " + carId));

        // Only include instructions in DTO if render=true and instructions are
        // available
        Instruction instructionsForDto = (render && instructions != null) ? instructions : null;
        TripDto tripDto = convertToTripDto(trip, route, waypointProgresses, render, instructionsForDto);

        // For TripResponse, we still return instructions separately (for backward
        // compatibility)
        Instruction responseInstructions = (render && instructions != null) ? instructions : null;

        return TripResponse.builder()
                .trip(tripDto)
                .instructions(responseInstructions)
                .currentLocation(currentLocation)
                .build();
    }

    /**
     * Get trip by ID with optional render parameter
     */
    public TripResponse getTripById(Long tripId, boolean render) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found with id: " + tripId));

        Route route = getRouteFromTrip(trip);
        List<Waypoint> originalWaypoints = getOriginalWaypoints(trip);

        // Get navigation state to build waypoint progresses
        NavigationState navState = redisStateStore.getNavigationState(trip.getCarId());
        List<WaypointProgress> waypointProgresses = new ArrayList<>();

        // Only use navigation state if it belongs to this trip (prevent cross-trip
        // contamination)
        if (navState != null && navState.getTripId() != null && navState.getTripId().equals(trip.getId())) {
            // Build waypoint progresses from navigation state
            waypointProgresses = buildWaypointProgressesFromState(route, navState, originalWaypoints,
                    trip.isIncludeOrigin());
        } else {
            // Build initial waypoint progresses (state doesn't exist or belongs to
            // different trip)
            if (navState != null && (navState.getTripId() == null || !navState.getTripId().equals(trip.getId()))) {
                log.warn(
                        "Navigation state for carId {} belongs to different trip (state.tripId: {}, current tripId: {}), using initial progress",
                        trip.getCarId(), navState.getTripId(), trip.getId());
            }
            waypointProgresses = buildInitialWaypointProgresses(route, originalWaypoints, trip.isIncludeOrigin());
        }

        // Get instructions if render=true (re-fetch from OSRM)
        Instruction instructions = null;
        if (render) {
            try {
                // Convert waypoints to double[][] format for OSRM
                List<double[]> waypointCoords = originalWaypoints.stream()
                        .map(wp -> new double[] { wp.getLatitude(), wp.getLongitude() })
                        .collect(Collectors.toList());

                // Re-fetch instructions from OSRM
                instructions = osrmClient.getInstructions(waypointCoords);
                log.debug("Fetched instructions for trip {} with render=true", tripId);
            } catch (Exception e) {
                log.warn("Could not fetch instructions for trip {}: {}", tripId, e.getMessage());
            }
        }

        TripDto tripDto = convertToTripDto(trip, route, waypointProgresses, render, instructions);

        // Try to get current location from Redis state or latest snapshot
        CurrentLocation currentLocation = getCurrentLocation(trip.getId(), trip, navState);

        return TripResponse.builder()
                .trip(tripDto)
                .instructions(instructions)
                .currentLocation(currentLocation)
                .build();
    }

    /**
     * Build waypoint progresses from navigation state
     * Reconstructs waypoint progress from saved state (waypointStatesJson)
     */
    public List<WaypointProgress> buildWaypointProgressesFromState(Route route, NavigationState state,
            List<Waypoint> originalWaypoints, boolean includeOrigin) {
        List<WaypointProgress> progresses = new ArrayList<>();

        // Load previous waypoint states from stored JSON
        Map<Integer, WaypointState> savedStates = loadWaypointStates(state.getWaypointStatesJson());
        Map<Integer, Instant> savedArrivedAt = loadWaypointArrivedAt(state.getWaypointStatesJson());

        // Determine which waypoints to track based on includeOrigin
        int startIndex = includeOrigin ? 0 : 1;

        List<double[]> polyline = route.getPolyline();
        List<Double> cumulativeDistances = route.getCumulativeDistances();

        // Track progress against original trip waypoints
        for (int i = startIndex; i < originalWaypoints.size(); i++) {
            Waypoint originalWaypoint = originalWaypoints.get(i);

            // Find closest point on current route to this original waypoint
            double minDistance = Double.MAX_VALUE;
            double waypointDistance = 0.0;

            // Find closest point on route polyline to original waypoint
            for (int j = 0; j < polyline.size(); j++) {
                double[] routePoint = polyline.get(j);
                double dist = com.gocavgo.Navigation.util.GeoMath.haversineDistance(
                        originalWaypoint.getLatitude(), originalWaypoint.getLongitude(),
                        routePoint[0], routePoint[1]);

                if (dist < minDistance) {
                    minDistance = dist;
                    waypointDistance = cumulativeDistances.get(j);
                }
            }

            double remainingDistance = waypointDistance - state.getDistanceTravelled();
            double remainingTime = remainingDistance > 0 ? remainingDistance / Math.max(state.getAvgSpeed(), 0.5) : 0.0;

            // Get saved state for this waypoint (use original waypoint index)
            WaypointState waypointState = savedStates.getOrDefault(i, WaypointState.APPROACHING);
            Instant arrivedAt = savedArrivedAt.get(i);

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
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
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
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
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
     * Build initial waypoint progresses
     */
    private List<WaypointProgress> buildInitialWaypointProgresses(Route route, List<Waypoint> originalWaypoints,
            boolean includeOrigin) {
        List<WaypointProgress> progresses = new ArrayList<>();
        List<Integer> legStopIndices = route.getLegStopIndices();
        List<Double> legCumulativeDistances = route.getLegCumulativeDistances();
        List<double[]> polyline = route.getPolyline();

        int startIndex = includeOrigin ? 0 : 1; // Skip origin if includeOrigin=false

        for (int i = startIndex; i < legStopIndices.size() && i < originalWaypoints.size(); i++) {
            int polylineIndex = legStopIndices.get(i);
            double waypointDistance = legCumulativeDistances.get(i);
            double[] waypoint = polyline.get(polylineIndex);
            Waypoint originalWaypoint = originalWaypoints.get(i);

            progresses.add(WaypointProgress.builder()
                    .waypointIndex(i)
                    .waypointId(originalWaypoint != null ? originalWaypoint.getId() : null)
                    .waypointName(originalWaypoint != null ? originalWaypoint.getName() : null)
                    .latitude(waypoint[0])
                    .longitude(waypoint[1])
                    .state(WaypointState.APPROACHING)
                    .arrivedAt(null)
                    .remainingDistance(waypointDistance)
                    .remainingTime(route.getTotalDuration() * (waypointDistance / route.getTotalDistance()))
                    .build());
        }

        return progresses;
    }

    /**
     * Convert Trip entity to DTO
     * 
     * @param render       If true, includes route polyline and other rendering data
     * @param instructions Optional instructions to include when render=true
     */
    private TripDto convertToTripDto(Trip trip, Route route, List<WaypointProgress> waypointProgresses,
            boolean render, Instruction instructions) {
        // Parse waypoints from JSON (now supports Waypoint with id and name)
        List<Waypoint> waypoints;
        try {
            waypoints = objectMapper.readValue(trip.getWaypointsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Waypoint.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to parse waypoints JSON", e);
            waypoints = new ArrayList<>();
        }

        // Create a map of waypoint index to waypoint (for id and name lookup)
        Map<Integer, Waypoint> waypointMap = new HashMap<>();
        for (int i = 0; i < waypoints.size(); i++) {
            waypointMap.put(i, waypoints.get(i));
        }

        // Convert waypoint progresses to DTOs
        List<TripDto.WaypointProgressDto> progressDtos = waypointProgresses.stream()
                .map(wp -> {
                    Waypoint waypoint = waypointMap.get(wp.getWaypointIndex());
                    return TripDto.WaypointProgressDto.builder()
                            .waypointIndex(wp.getWaypointIndex())
                            .waypointId(waypoint != null ? waypoint.getId() : null)
                            .waypointName(waypoint != null ? waypoint.getName() : null)
                            .latitude(wp.getLatitude())
                            .longitude(wp.getLongitude())
                            .state(wp.getState().name())
                            .arrivedAt(wp.getArrivedAt())
                            .remainingDistance(wp.getRemainingDistance())
                            .remainingTime(wp.getRemainingTime())
                            .build();
                })
                .collect(Collectors.toList());

        TripDto.TripDtoBuilder builder = TripDto.builder()
                .id(trip.getId())
                .carId(trip.getCarId())
                .status(trip.getStatus())
                .waypoints(waypoints)
                .waypointProgresses(progressDtos)
                .includeOrigin(trip.isIncludeOrigin())
                .isCityTrip(trip.isCityTrip())
                .createdAt(trip.getCreatedAt())
                .completedAt(trip.getCompletedAt());

        // Include route data if render=true
        if (render && route != null) {
            builder.route(convertRouteToDto(route));
        }

        // Include instructions if render=true and instructions are provided
        if (render && instructions != null) {
            builder.instructions(instructions);
        }

        return builder.build();
    }

    /**
     * Convert Route model to RouteDto
     */
    private RouteDto convertRouteToDto(Route route) {
        // Convert polyline from double[][] to List<List<Double>>
        List<List<Double>> polylineDto = route.getPolyline().stream()
                .map(point -> {
                    List<Double> pointList = new ArrayList<>();
                    pointList.add(point[0]); // lat
                    pointList.add(point[1]); // lon
                    return pointList;
                })
                .collect(Collectors.toList());

        return RouteDto.builder()
                .polyline(polylineDto)
                .cumulativeDistances(route.getCumulativeDistances())
                .totalDistance(route.getTotalDistance())
                .totalDuration(route.getTotalDuration())
                .legStopIndices(route.getLegStopIndices())
                .legCumulativeDistances(route.getLegCumulativeDistances())
                .legDurations(route.getLegDurations())
                .build();
    }

    /**
     * Get route from trip (deserialize from JSON)
     */
    public Route getRouteFromTrip(Trip trip) {
        try {
            return objectMapper.readValue(trip.getRouteJson(), Route.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize route from trip {}", trip.getId(), e);
            throw new RuntimeException("Failed to load route", e);
        }
    }

    /**
     * Get original waypoints from trip
     */
    public List<Waypoint> getOriginalWaypoints(Trip trip) {
        try {
            return objectMapper.readValue(trip.getWaypointsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Waypoint.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize waypoints from trip {}", trip.getId(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Update trip status
     */
    @Transactional
    public void updateTripStatus(String carId, TripStatus status) {
        Trip trip = tripRepository.findMostRecentByCarIdAndStatuses(
                carId,
                java.util.Arrays.asList(TripStatus.ACTIVE, TripStatus.CREATED)).orElse(null);

        if (trip != null) {
            trip.setStatus(status);
            if (status == TripStatus.COMPLETED) {
                trip.setCompletedAt(Instant.now());
            }
            tripRepository.save(trip);

            // Publish trip update on completion
            if (status == TripStatus.COMPLETED) {
                try {
                    TripResponse tripResponse = getTripById(trip.getId(), false);
                    tripEventPublisher.publishTripUpdate(tripResponse);
                } catch (Exception e) {
                    log.error("Failed to publish trip completion event for tripId: {}", trip.getId(), e);
                }
            }
        }
    }

    /**
     * Delete a trip by ID regardless of its current state.
     * Also cleans up the associated Redis navigation state.
     */
    @Transactional
    public void deleteTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found with id: " + tripId));

        String carId = trip.getCarId();

        // Delete navigation snapshots for this trip
        org.springframework.data.domain.Pageable pageable = PageRequest.of(0, Integer.MAX_VALUE);
        Page<NavigationSnapshot> snapshots = snapshotRepository.findByTripIdOrderBySnapshotTimestampDesc(tripId,
                pageable);
        if (!snapshots.isEmpty()) {
            snapshotRepository.deleteAll(snapshots.getContent());
            log.info("Deleted {} navigation snapshots for tripId: {}", snapshots.getNumberOfElements(), tripId);
        }

        // Delete Redis navigation state for this car (ensure no stale state remains)
        redisStateStore.deleteNavigationState(carId);
        log.info("Deleted Redis navigation state for carId: {}", carId);

        // Delete the trip from the database
        tripRepository.delete(trip);
        log.info("Deleted trip {} for carId: {}", tripId, carId);
    }

    /**
     * Get all trips with pagination
     * 
     * @param page    Page number (0-indexed)
     * @param size    Page size
     * @param sortBy  Field to sort by
     * @param sortDir Sort direction (ASC or DESC)
     * @param render  If true, includes route polyline data
     * @return Page of TripDto
     */
    public Page<TripDto> getAllTrips(int page, int size, String sortBy, String sortDir, boolean render) {
        // Create sort direction
        Sort.Direction direction = sortDir.equalsIgnoreCase("ASC") ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortBy);

        // Create pageable
        Pageable pageable = PageRequest.of(page, size, sort);

        // Get trips from repository
        Page<Trip> tripsPage = tripRepository.findAll(pageable);

        // Convert to DTOs
        return tripsPage.map(trip -> {
            try {
                Route route = getRouteFromTrip(trip);
                List<Waypoint> originalWaypoints = getOriginalWaypoints(trip);

                // Build waypoint progresses
                NavigationState navState = redisStateStore.getNavigationState(trip.getCarId());
                List<WaypointProgress> waypointProgresses;

                // Only use navigation state if it belongs to this trip (prevent cross-trip
                // contamination)
                if (navState != null && navState.getTripId() != null && navState.getTripId().equals(trip.getId())) {
                    waypointProgresses = buildWaypointProgressesFromState(route, navState, originalWaypoints,
                            trip.isIncludeOrigin());
                } else {
                    if (navState != null
                            && (navState.getTripId() == null || !navState.getTripId().equals(trip.getId()))) {
                        log.debug(
                                "Navigation state for carId {} in getAllTrips belongs to different trip, using initial progress",
                                trip.getCarId());
                    }
                    waypointProgresses = buildInitialWaypointProgresses(route, originalWaypoints,
                            trip.isIncludeOrigin());
                }

                // Fetch instructions if render=true
                Instruction instructions = null;
                if (render) {
                    try {
                        List<double[]> waypointCoords = originalWaypoints.stream()
                                .map(wp -> new double[] { wp.getLatitude(), wp.getLongitude() })
                                .collect(Collectors.toList());
                        instructions = osrmClient.getInstructions(waypointCoords);
                    } catch (Exception e) {
                        log.warn("Could not fetch instructions for trip {}: {}", trip.getId(), e.getMessage());
                    }
                }

                return convertToTripDto(trip, route, waypointProgresses, render, instructions);
            } catch (Exception e) {
                log.error("Error converting trip {} to DTO", trip.getId(), e);
                // Return minimal DTO on error
                try {
                    List<Waypoint> waypoints = getOriginalWaypoints(trip);
                    return TripDto.builder()
                            .id(trip.getId())
                            .carId(trip.getCarId())
                            .status(trip.getStatus())
                            .waypoints(waypoints)
                            .includeOrigin(trip.isIncludeOrigin())
                            .isCityTrip(trip.isCityTrip())
                            .createdAt(trip.getCreatedAt())
                            .completedAt(trip.getCompletedAt())
                            .build();
                } catch (Exception ex) {
                    log.error("Failed to create minimal DTO for trip {}", trip.getId(), ex);
                    throw new RuntimeException("Failed to convert trip to DTO", ex);
                }
            }
        });
    }

    /**
     * Update trip route (e.g. after rerouting)
     */
    @Transactional
    public void updateTripRoute(Long tripId, Route newRoute) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found with id: " + tripId));

        try {
            String routeJson = objectMapper.writeValueAsString(newRoute);
            trip.setRouteJson(routeJson);
            tripRepository.save(trip);
            log.info("Updated route for tripId: {} (rerouted result saved)", tripId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize new route for tripId: {}", tripId, e);
            throw new RuntimeException("Failed to save new route", e);
        }
    }

    /**
     * Get current location with carId from trip
     */
    private CurrentLocation getCurrentLocation(Long tripId, Trip trip, NavigationState navState) {
        // First try Redis state (only if it belongs to this trip)
        if (navState != null && navState.getTripId() != null && navState.getTripId().equals(tripId) &&
                navState.getCurrentLatitude() != null && navState.getCurrentLongitude() != null) {
            return CurrentLocation.builder()
                    .carId(trip.getCarId())
                    .latitude(navState.getCurrentLatitude())
                    .longitude(navState.getCurrentLongitude())
                    .speed(navState.getCurrentSpeed() != null ? navState.getCurrentSpeed() : 0.0)
                    .heading(navState.getCurrentHeading())
                    .timestamp(navState.getCurrentLocationTimestamp() != null ? navState.getCurrentLocationTimestamp()
                            : Instant.now())
                    .build();
        }

        // Fallback to latest snapshot
        com.gocavgo.Navigation.model.NavigationSnapshot snapshot = snapshotService.getLatestSnapshot(tripId);
        if (snapshot != null) {
            CurrentLocation location = snapshotService.loadCurrentLocationFromSnapshot(snapshot);
            if (location != null) {
                // Ensure carId matches trip
                location.setCarId(trip.getCarId());
            }
            return location;
        }

        return null;
    }
}
