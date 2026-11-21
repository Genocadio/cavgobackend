package com.gocavgo.delivary.util;

import java.time.Duration;
import java.time.Instant;

/** Shared, deterministic time-window checks for post-delivery actions. */
public final class DeliveryWindowUtil {

    public static final Duration PROOF_PHOTO_WINDOW = Duration.ofMinutes(5);
    public static final Duration RATING_WINDOW = Duration.ofDays(30);

    private DeliveryWindowUtil() {
    }

    /**
     * Inclusive window: the action becomes available at delivery and remains
     * available until exactly {@code window} after delivery.
     */
    public static boolean isWithin(Instant deliveredAt, Instant now, Duration window) {
        return deliveredAt != null && now != null
                && !now.isBefore(deliveredAt)
                && !now.isAfter(deliveredAt.plus(window));
    }
}
