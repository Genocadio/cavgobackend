package com.gocavgo.delivary.repository.user;

import com.gocavgo.delivary.entity.user.DriverProfileEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverProfileRepository {
    DriverProfileEntity save(DriverProfileEntity profile);
    Optional<DriverProfileEntity> findById(UUID id);
    Optional<DriverProfileEntity> findByUserId(Long userId);
    List<DriverProfileEntity> findByCompanyId(UUID companyId);
    void deleteById(UUID id);

    /**
     * Touch last_seen_at and set status to ONLINE for the given user's driver profile.
     * No-op if the user has no driver profile.
     */
    void touchLastSeen(Long userId);

    /**
     * Mark all ONLINE drivers whose last_seen_at is older than the threshold as OFFLINE.
     * Returns the number of drivers affected.
     */
    int markStaleDriversOffline(java.time.Instant threshold);
}
