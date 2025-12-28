package com.gocavgo.Navigation.service;

import com.gocavgo.Navigation.model.Trip;
import com.gocavgo.Navigation.model.enums.TripStatus;
import com.gocavgo.Navigation.store.NavigationSnapshotRepository;
import com.gocavgo.Navigation.store.RedisStateStore;
import com.gocavgo.Navigation.store.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResetService {
    
    private final TripRepository tripRepository;
    private final NavigationSnapshotRepository snapshotRepository;
    private final RedisStateStore redisStateStore;
    
    /**
     * Reset the system by deleting all inactive trips and active trips older than 24 hours.
     * Also deletes associated navigation state and snapshots.
     * 
     * @return ResetResult containing counts of deleted items
     */
    @Transactional
    public ResetResult resetSystem() {
        log.info("Starting system reset - deleting inactive trips and old active trips");
        
        Instant cutoffTime = Instant.now().minus(24, ChronoUnit.HOURS);
        
        // Find all trips to delete:
        // 1. All non-ACTIVE trips (COMPLETED, CANCELLED, CREATED)
        // 2. ACTIVE trips older than 24 hours
        List<Trip> allTrips = tripRepository.findAll();
        
        List<Trip> tripsToDelete = allTrips.stream()
                .filter(trip -> {
                    // Delete if not ACTIVE
                    if (trip.getStatus() != TripStatus.ACTIVE) {
                        return true;
                    }
                    // Delete ACTIVE trips older than 24 hours
                    if (trip.getStatus() == TripStatus.ACTIVE && 
                        trip.getCreatedAt().isBefore(cutoffTime)) {
                        return true;
                    }
                    return false;
                })
                .collect(Collectors.toList());
        
        int deletedTrips = 0;
        int deletedSnapshots = 0;
        int deletedRedisStates = 0;
        Set<String> carIdsProcessed = new java.util.HashSet<>();
        
        for (Trip trip : tripsToDelete) {
            try {
                // Delete navigation snapshots for this trip
                // Get all snapshots for this trip and delete them
                org.springframework.data.domain.Pageable pageable = 
                    org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE);
                org.springframework.data.domain.Page<com.gocavgo.Navigation.model.NavigationSnapshot> snapshotPage = 
                    snapshotRepository.findByTripIdOrderBySnapshotTimestampDesc(trip.getId(), pageable);
                List<com.gocavgo.Navigation.model.NavigationSnapshot> snapshots = snapshotPage.getContent();
                if (!snapshots.isEmpty()) {
                    snapshotRepository.deleteAll(snapshots);
                    deletedSnapshots += snapshots.size();
                }
                
                // Delete Redis state for this car (if not already deleted)
                String carId = trip.getCarId();
                if (!carIdsProcessed.contains(carId)) {
                    // Check if there are other active trips for this car that we're keeping
                    boolean hasActiveTrip = allTrips.stream()
                            .filter(t -> t.getCarId().equals(carId))
                            .filter(t -> t.getStatus() == TripStatus.ACTIVE)
                            .filter(t -> t.getCreatedAt().isAfter(cutoffTime))
                            .anyMatch(t -> !tripsToDelete.contains(t));
                    
                    // Only delete Redis state if no active trips remain for this car
                    if (!hasActiveTrip) {
                        redisStateStore.deleteNavigationState(carId);
                        deletedRedisStates++;
                        carIdsProcessed.add(carId);
                    }
                }
                
                // Delete the trip
                tripRepository.delete(trip);
                deletedTrips++;
                
            } catch (Exception e) {
                log.error("Error deleting trip {}: {}", trip.getId(), e.getMessage(), e);
            }
        }
        
        log.info("System reset completed: deleted {} trips, {} snapshots, {} Redis states", 
                deletedTrips, deletedSnapshots, deletedRedisStates);
        
        return new ResetResult(deletedTrips, deletedSnapshots, deletedRedisStates);
    }
    
    /**
     * Result of reset operation
     */
    public static class ResetResult {
        private final int deletedTrips;
        private final int deletedSnapshots;
        private final int deletedRedisStates;
        
        public ResetResult(int deletedTrips, int deletedSnapshots, int deletedRedisStates) {
            this.deletedTrips = deletedTrips;
            this.deletedSnapshots = deletedSnapshots;
            this.deletedRedisStates = deletedRedisStates;
        }
        
        public int getDeletedTrips() {
            return deletedTrips;
        }
        
        public int getDeletedSnapshots() {
            return deletedSnapshots;
        }
        
        public int getDeletedRedisStates() {
            return deletedRedisStates;
        }
    }
}

