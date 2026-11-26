package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record AssignDriverInput(
        @NotNull UUID packageId,
        @NotNull Long driverId,
        @NotNull Long assignedBy,
        String notes,
        Long tripId,
        UUID batchId,
        Instant objectionDeadline,
        Instant acceptedAt
) {
    public AssignDriverInput(UUID packageId, Long driverId, Long assignedBy, String notes, Long tripId) {
        this(packageId, driverId, assignedBy, notes, tripId, null, null, null);
    }
}
