package com.gocavgo.delivary.service.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter for package delivery confirmation code verification.
 * Enforces a 60-second cooldown after 5 consecutive failed attempts per package
 * to prevent brute-force code guessing attacks.
 */
@Slf4j
@Component
public class DeliveryCodeRateLimiter {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long COOLDOWN_MS = 60_000L; // 60 seconds

    private record AttemptRecord(int failedAttempts, long lockedUntilMs, long lastAttemptMs) {}

    private final Map<UUID, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Checks if verification for this package is currently rate-limited (locked).
     * @throws RuntimeException if currently in cooldown
     */
    public void checkRateLimit(UUID packageId) {
        cleanStaleEntries();
        var record = attempts.get(packageId);
        if (record != null) {
            long now = System.currentTimeMillis();
            if (now < record.lockedUntilMs()) {
                long remainingSec = (record.lockedUntilMs() - now + 999) / 1000;
                log.warn("confirmDelivery rate limit active for packageId={}, remainingSec={}", packageId, remainingSec);
                throw new RuntimeException("Too many failed attempts. Please wait " + remainingSec + "s before trying again.");
            }
        }
    }

    /**
     * Records a failed code verification attempt and activates cooldown if threshold reached.
     */
    public void recordFailedAttempt(UUID packageId) {
        long now = System.currentTimeMillis();
        attempts.compute(packageId, (id, current) -> {
            if (current == null || now >= current.lockedUntilMs()) {
                int newAttempts = (current == null || now - current.lastAttemptMs() > COOLDOWN_MS * 5) ? 1 : (current.failedAttempts() + 1);
                if (newAttempts >= MAX_FAILED_ATTEMPTS) {
                    log.warn("confirmDelivery lockout triggered for packageId={} (failedAttempts={})", packageId, newAttempts);
                    return new AttemptRecord(newAttempts, now + COOLDOWN_MS, now);
                }
                return new AttemptRecord(newAttempts, 0L, now);
            } else {
                return current;
            }
        });
    }

    /**
     * Clears failed attempt tracking upon successful code verification.
     */
    public void recordSuccess(UUID packageId) {
        attempts.remove(packageId);
    }

    private void cleanStaleEntries() {
        if (attempts.size() > 1000) {
            long cutoff = System.currentTimeMillis() - (COOLDOWN_MS * 10);
            attempts.entrySet().removeIf(entry -> entry.getValue().lastAttemptMs() < cutoff);
        }
    }
}
