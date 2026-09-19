package com.gocavgo.delivary.config;

import com.gocavgo.delivary.entity.trip.CavgoTripEntity;
import com.gocavgo.delivary.repository.trip.CavgoTripJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodically cleans up cavgotrips trip mirrors whose retention window has lapsed
 * (terminal trips such as COMPLETED / CANCELLED past the 10-hour expiry).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CavgoTripCleanupScheduler {

    private final CavgoTripJpaRepository tripRepository;

    @Scheduled(fixedRate = 15 * 60 * 1000) // every 15 minutes
    @Transactional
    public void cleanupExpiredTrips() {
        List<CavgoTripEntity> expired = tripRepository.findExpiredTrips(Instant.now());
        if (!expired.isEmpty()) {
            tripRepository.deleteAll(expired);
            log.info("[CavgoTripCleanup] Deleted {} expired trip(s)", expired.size());
        }
    }
}