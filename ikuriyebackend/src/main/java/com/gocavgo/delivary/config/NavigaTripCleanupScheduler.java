package com.gocavgo.delivary.config;

import com.gocavgo.delivary.entity.naviga.NavigaTripEntity;
import com.gocavgo.delivary.repository.naviga.NavigaTripJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodically cleans up Naviga trip records:
 * <ul>
 *   <li>Trips whose {@code expires_at} has passed (completed trips past the 10-hour window)</li>
 *   <li>Trips with status {@code DELETED} (should have been deleted by listener but kept as safety net)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigaTripCleanupScheduler {

    private final NavigaTripJpaRepository tripRepository;

    /**
     * Every 15 minutes, delete trips past their expiry and any lingering DELETED trips.
     */
    @Scheduled(fixedRate = 15 * 60 * 1000) // every 15 minutes
    @Transactional
    public void cleanupExpiredTrips() {
        Instant now = Instant.now();

        // 1. Delete expired trips (completed + 10 hours)
        List<NavigaTripEntity> expired = tripRepository.findExpiredTrips(now);
        if (!expired.isEmpty()) {
            tripRepository.deleteAll(expired);
            log.info("[NavigaCleanup] Deleted {} expired trip(s)", expired.size());
        }

        // 2. Delete any lingering DELETED trips (safety net)
        int deletedCount = tripRepository.deleteDeletedTrips();
        if (deletedCount > 0) {
            log.info("[NavigaCleanup] Deleted {} lingering DELETED trip(s)", deletedCount);
        }
    }
}
