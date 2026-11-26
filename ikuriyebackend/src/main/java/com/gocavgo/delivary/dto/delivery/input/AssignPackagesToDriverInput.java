package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AssignPackagesToDriverInput(
        @NotEmpty List<UUID> packageIds,
        @NotNull Long driverId,
        Long tripId,
        String notes
) {
}
