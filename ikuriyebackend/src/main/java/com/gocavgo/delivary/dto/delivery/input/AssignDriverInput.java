package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignDriverInput(
        @NotNull UUID packageId,
        @NotNull Long driverId,
        @NotNull Long assignedBy,
        String notes
) {
}
