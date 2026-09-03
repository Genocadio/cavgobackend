package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileJpaRepository extends JpaRepository<DriverProfileEntity, UUID> {
    Optional<DriverProfileEntity> findByUserId(Long userId);
    List<DriverProfileEntity> findByCompanyId(UUID companyId);

    /**
     * Atomically set last_seen_at = now and status = ONLINE for the given user's driver profile.
     * Lightweight UPDATE — avoids loading the entity.
     */
    @Modifying
    @Query("UPDATE DriverProfileEntity d SET d.lastSeenAt = :now, d.status = com.gocavgo.delivary.enums.user.DriverStatus.ONLINE WHERE d.user.id = :userId")
    int touchLastSeen(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Mark all drivers as OFFLINE whose last_seen_at is older than the given threshold.
     * Returns the number of drivers that were set offline.
     */
    @Modifying
    @Query("UPDATE DriverProfileEntity d SET d.status = com.gocavgo.delivary.enums.user.DriverStatus.OFFLINE WHERE d.status = com.gocavgo.delivary.enums.user.DriverStatus.ONLINE AND d.lastSeenAt < :threshold")
    int markStaleDriversOffline(@Param("threshold") Instant threshold);
}
