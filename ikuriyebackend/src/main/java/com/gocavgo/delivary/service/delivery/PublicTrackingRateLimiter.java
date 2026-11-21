package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.exception.BusinessValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory abuse control for unauthenticated public tracking lookups.
 *
 * <p>The tracking code is a static bearer capability, so repeated guessing and
 * bulk enumeration are throttled per client key. Entries expire with the window
 * and the map is pruned when it grows beyond a safe bound.</p>
 */
@Slf4j
@Component
public class PublicTrackingRateLimiter {

    static final int MAX_REQUESTS_PER_WINDOW = 40;
    static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 5_000;

    private record Window(int count, long resetAtMs) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Throws a validation error when the client has exhausted its window. */
    public void checkRateLimit(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        prune();

        long now = System.currentTimeMillis();
        windows.compute(key, (ignored, current) -> {
            if (current == null || now >= current.resetAtMs()) {
                return new Window(1, now + WINDOW_MS);
            }
            if (current.count() >= MAX_REQUESTS_PER_WINDOW) {
                long remainingSec = (current.resetAtMs() - now + 999) / 1000;
                log.warn("publicTracking rate limit active for clientKey={}, remainingSec={}", key, remainingSec);
                throw new BusinessValidationException(
                        "Too many tracking lookups. Please wait " + remainingSec + "s before trying again.");
            }
            return new Window(current.count() + 1, current.resetAtMs());
        });
    }

    private void prune() {
        if (windows.size() <= MAX_TRACKED_CLIENTS) return;
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> entry.getValue().resetAtMs() <= now);
    }
}
