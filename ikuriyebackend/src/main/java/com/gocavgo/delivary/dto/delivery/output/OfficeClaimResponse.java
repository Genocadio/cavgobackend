package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;

import java.time.Instant;
import java.util.UUID;

/** GraphQL projection of a destination-office claim taken directly from a driver. */
public record OfficeClaimResponse(
        UUID id,
        UUID packageId,
        Long driverId,
        Long claimedBy,
        UUID officeLocationId,
        Instant claimedAt,
        Instant disputeDeadline,
        Instant disputedAt,
        String disputeReason,
        String notes
) {
    /** Short alias used by clients and tests. */
    public UUID officeId() {
        return officeLocationId;
    }

    public static OfficeClaimResponse from(OfficeClaimEntity entity) {
        return new OfficeClaimResponse(
                entity.getId(),
                entity.getPackageId(),
                entity.getDriverId(),
                entity.getClaimedBy(),
                entity.getOfficeLocationId(),
                entity.getClaimedAt(),
                entity.getDisputeDeadline(),
                entity.getDisputedAt(),
                entity.getDisputeReason(),
                entity.getNotes()
        );
    }
}
