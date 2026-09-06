package com.gocavgo.delivary.repository.naviga;

import com.gocavgo.delivary.entity.naviga.NavigaTripEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NavigaTripJpaRepository extends JpaRepository<NavigaTripEntity, Long> {

    /**
     * Find all trips whose expires_at has passed (completed trips past the 10-hour window).
     */
    @Query("SELECT t FROM NavigaTripEntity t WHERE t.expiresAt IS NOT NULL AND t.expiresAt <= :now")
    List<NavigaTripEntity> findExpiredTrips(Instant now);

    /**
     * Delete all trips with status DELETED (immediate cleanup).
     */
    @Modifying
    @Query("DELETE FROM NavigaTripEntity t WHERE t.status = 'DELETED'")
    int deleteDeletedTrips();
}
