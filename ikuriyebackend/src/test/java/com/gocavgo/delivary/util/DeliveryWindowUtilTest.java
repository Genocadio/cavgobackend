package com.gocavgo.delivary.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryWindowUtilTest {

    private final Instant deliveredAt = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void proofWindowAllowsFiveMinutesButNotLater() {
        assertTrue(DeliveryWindowUtil.isWithin(
                deliveredAt, deliveredAt.plus(DeliveryWindowUtil.PROOF_PHOTO_WINDOW),
                DeliveryWindowUtil.PROOF_PHOTO_WINDOW));
        assertFalse(DeliveryWindowUtil.isWithin(
                deliveredAt, deliveredAt.plus(DeliveryWindowUtil.PROOF_PHOTO_WINDOW).plusNanos(1),
                DeliveryWindowUtil.PROOF_PHOTO_WINDOW));
        assertFalse(DeliveryWindowUtil.isWithin(
                deliveredAt, deliveredAt.minusNanos(1), DeliveryWindowUtil.PROOF_PHOTO_WINDOW));
    }

    @Test
    void ratingWindowAllowsThirtyDaysButNotLater() {
        assertTrue(DeliveryWindowUtil.isWithin(
                deliveredAt, deliveredAt.plus(DeliveryWindowUtil.RATING_WINDOW),
                DeliveryWindowUtil.RATING_WINDOW));
        assertFalse(DeliveryWindowUtil.isWithin(
                deliveredAt, deliveredAt.plus(DeliveryWindowUtil.RATING_WINDOW).plusNanos(1),
                DeliveryWindowUtil.RATING_WINDOW));
    }

    @Test
    void missingDeliveryTimeFailsClosed() {
        assertFalse(DeliveryWindowUtil.isWithin(
                null, Instant.now(), DeliveryWindowUtil.RATING_WINDOW));
    }
}
