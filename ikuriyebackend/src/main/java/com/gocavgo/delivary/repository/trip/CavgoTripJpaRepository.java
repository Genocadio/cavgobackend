package com.gocavgo.delivary.repository.trip;

import com.gocavgo.delivary.entity.trip.CavgoTripEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface CavgoTripJpaRepository extends JpaRepository<CavgoTripEntity, Long> {

    /**
     * All trips associated with a driver (from the trip service vehicle snapshot).
     */
    List<CavgoTripEntity> findByDriverId(Long driverId);

    /**
     * Trips for a driver in the given status (e.g. SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED).
     */
    List<CavgoTripEntity> findByDriverIdAndStatus(Long driverId, String status);

    List<CavgoTripEntity> findByStatus(String status);

    /**
     * Find trips whose expires_at has passed (terminal trips past the retention window).
     */
    @Query("SELECT t FROM CavgoTripEntity t WHERE t.expiresAt IS NOT NULL AND t.expiresAt <= :now")
    List<CavgoTripEntity> findExpiredTrips(Instant now);
}