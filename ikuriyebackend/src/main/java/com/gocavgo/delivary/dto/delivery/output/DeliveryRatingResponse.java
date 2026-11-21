package com.gocavgo.delivary.dto.delivery.output;

import java.time.Instant;
import java.util.UUID;

/** Client-facing delivery rating. Rater identity is intentionally omitted. */
public record DeliveryRatingResponse(
        UUID id,
        UUID packageId,
        int score,
        String comment,
        Instant createdAt
) {
}
