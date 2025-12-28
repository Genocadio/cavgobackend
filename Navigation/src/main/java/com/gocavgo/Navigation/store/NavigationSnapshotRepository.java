package com.gocavgo.Navigation.store;

import com.gocavgo.Navigation.model.NavigationSnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface NavigationSnapshotRepository extends JpaRepository<NavigationSnapshot, Long> {
    
    /**
     * Get the most recent snapshot for a trip
     */
    @Query("SELECT n FROM NavigationSnapshot n WHERE n.tripId = :tripId ORDER BY n.snapshotTimestamp DESC LIMIT 1")
    Optional<NavigationSnapshot> findLatestByTripId(@Param("tripId") Long tripId);
    
    /**
     * Get paginated history of snapshots for a trip, ordered by timestamp descending
     */
    Page<NavigationSnapshot> findByTripIdOrderBySnapshotTimestampDesc(Long tripId, Pageable pageable);
    
    /**
     * Delete old snapshots before a certain timestamp
     */
    void deleteByTripIdAndSnapshotTimestampBefore(Long tripId, Instant before);
}

