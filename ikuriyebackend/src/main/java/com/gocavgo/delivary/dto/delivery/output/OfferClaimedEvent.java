package com.gocavgo.delivary.dto.delivery.output;

import java.util.UUID;

public record OfferClaimedEvent(
        UUID packageId,
        String trackingCode,
        UUID transferId,
        Long claimedByUserId,
        String claimedByRole,
        String claimedAt
) {
}
