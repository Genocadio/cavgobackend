package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignPackageTripInput(
        @NotNull UUID packageId,
        @NotNull UUID tripId
) {
}
