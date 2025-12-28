package com.gocavgo.Navigation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.model.NavigationSnapshot;
import com.gocavgo.Navigation.model.NavigationState;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.WaypointProgress;
import com.gocavgo.Navigation.model.dto.CurrentLocation;
import com.gocavgo.Navigation.store.NavigationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavigationSnapshotService {
    private final NavigationSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    
    /**
     * Save a snapshot of navigation state to database
     */
    @Transactional
    public void saveSnapshot(String carId, Long tripId, NavigationState state, Route route,
                            List<WaypointProgress> progresses, CurrentLocation currentLocation) {
        try {
            // Serialize waypoint progresses to JSON
            String waypointProgressesJson = objectMapper.writeValueAsString(progresses);
            
            // Serialize current location to JSON
            String currentLocationJson = null;
            if (currentLocation != null) {
                currentLocationJson = objectMapper.writeValueAsString(currentLocation);
            }
            
            // Create snapshot entity
            NavigationSnapshot snapshot = NavigationSnapshot.builder()
                    .tripId(tripId)
                    .carId(carId)
                    .snapshotTimestamp(Instant.now())
                    .waypointProgressesJson(waypointProgressesJson)
                    .currentLocationJson(currentLocationJson)
                    .distanceTravelled(state.getDistanceTravelled())
                    .lastSnappedIndex(state.getLastSnappedIndex())
                    .build();
            
            snapshotRepository.save(snapshot);
            log.debug("Saved navigation snapshot for tripId: {}, carId: {}", tripId, carId);
        } catch (JsonProcessingException e) {
            log.error("Failed to save navigation snapshot for tripId: {}, carId: {}", tripId, carId, e);
        }
    }
    
    /**
     * Get the latest snapshot for a trip
     */
    public NavigationSnapshot getLatestSnapshot(Long tripId) {
        return snapshotRepository.findLatestByTripId(tripId).orElse(null);
    }
    
    /**
     * Check if snapshot should be saved immediately (state change detected)
     */
    public boolean shouldSaveSnapshotImmediately(List<WaypointProgress> oldProgresses, 
                                                 List<WaypointProgress> newProgresses) {
        if (oldProgresses == null || oldProgresses.isEmpty()) {
            return false; // No previous state to compare
        }
        
        // Check if any waypoint state changed (APPROACHING -> ARRIVED -> DONE)
        for (WaypointProgress newProgress : newProgresses) {
            WaypointProgress oldProgress = oldProgresses.stream()
                    .filter(p -> p.getWaypointIndex() == newProgress.getWaypointIndex())
                    .findFirst()
                    .orElse(null);
            
            if (oldProgress != null) {
                // State changed (e.g., APPROACHING -> ARRIVED, ARRIVED -> DONE)
                if (oldProgress.getState() != newProgress.getState()) {
                    log.debug("Waypoint state changed: index {} from {} to {}", 
                            newProgress.getWaypointIndex(), oldProgress.getState(), newProgress.getState());
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Load waypoint progresses from snapshot JSON
     */
    public List<WaypointProgress> loadWaypointProgressesFromSnapshot(NavigationSnapshot snapshot) {
        if (snapshot == null || snapshot.getWaypointProgressesJson() == null) {
            return new ArrayList<>();
        }
        
        try {
            return objectMapper.readValue(snapshot.getWaypointProgressesJson(),
                    new TypeReference<List<WaypointProgress>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize waypoint progresses from snapshot {}", snapshot.getId(), e);
            return new ArrayList<>();
        }
    }
    
    /**
     * Load current location from snapshot JSON
     */
    public CurrentLocation loadCurrentLocationFromSnapshot(NavigationSnapshot snapshot) {
        if (snapshot == null || snapshot.getCurrentLocationJson() == null) {
            return null;
        }
        
        try {
            return objectMapper.readValue(snapshot.getCurrentLocationJson(), CurrentLocation.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize current location from snapshot {}", snapshot.getId(), e);
            return null;
        }
    }
}

