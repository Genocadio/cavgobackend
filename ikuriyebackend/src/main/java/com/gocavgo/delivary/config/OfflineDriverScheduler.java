package com.gocavgo.delivary.config;

import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically marks drivers as OFFLINE when they haven't sent any request
 * (HTTP or WebSocket) for more than 1 hour. The heartbeat is recorded by
 * {@link com.gocavgo.delivary.security.NexxauthJwtAuthenticationFilter} and
 * {@link SecurityWebSocketGraphQlInterceptor} via
 * {@code DriverProfileRepository.touchLastSeen()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflineDriverScheduler {

    private static final long STALE_THRESHOLD_MS = 60 * 60 * 1000; // 1 hour

    private final DriverProfileRepository driverProfileRepository;

    @Scheduled(fixedRate = 5 * 60 * 1000) // every 5 minutes
    @Transactional
    public void markStaleDriversOffline() {
        Instant threshold = Instant.now().minusMillis(STALE_THRESHOLD_MS);
        int count = driverProfileRepository.markStaleDriversOffline(threshold);
        if (count > 0) {
            log.info("OfflineDriverScheduler: marked {} driver(s) OFFLINE (no activity for >1hr)", count);
        }
    }
}
