package com.gocavgo.delivary.dto.delivery.output;

import java.time.Instant;
import java.util.List;

/**
 * Privacy-safe projection for shareable public tracking links.
 *
 * <p>Deliberately excludes sender/receiver names and phones, exact coordinates,
 * declared value, media URLs, custodian details, vehicle snapshots, company/trip
 * identifiers and delivery codes. The tracking code itself is the bearer secret.</p>
 */
public record PublicTrackedPackage(
        String trackingCode,
        String deliveryType,
        String status,
        String origin,
        String destination,
        Instant createdAt,
        Instant updatedAt,
        List<PublicTrackingEvent> events
) {
    /** Timeline entry with no actor id or free-text notes, which may contain PII. */
    public record PublicTrackingEvent(
            String eventType,
            Instant createdAt
    ) {
    }
}
