package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;

import java.time.Instant;
import java.util.UUID;

/** GraphQL projection of a direct office-to-driver assignment. */
public record DriverAssignmentResponse(
        UUID id,
        UUID packageId,
        Long driverId,
        Long assignedBy,
        Instant assignedAt,
        Instant acceptedAt,
        Instant objectionDeadline,
        Instant objectedAt,
        String objectionReason,
        String notes,
        UUID batchId
) {
    public static DriverAssignmentResponse from(PackageAssignmentEntity entity) {
        var acceptedAt = entity.getAcceptedAt() != null ? entity.getAcceptedAt() : entity.getAssignedAt();
        var deadline = entity.getObjectionDeadline() != null
                ? entity.getObjectionDeadline()
                : entity.getAssignedAt().plusSeconds(10L * 60);
        return new DriverAssignmentResponse(
                entity.getId(),
                entity.getPackageId(),
                entity.getDriverId(),
                entity.getAssignedBy(),
                entity.getAssignedAt(),
                acceptedAt,
                deadline,
                entity.getObjectedAt(),
                entity.getObjectionReason(),
                entity.getNotes(),
                entity.getBatchId()
        );
    }
}
