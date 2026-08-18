package com.gocavgo.delivary.dto.delivery.input;

import com.gocavgo.delivary.enums.delivery.PackageStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdatePackageStatusInput(
        @NotNull UUID packageId,
        @NotNull Long actorId,
        @NotNull PackageStatus status,
        String notes
) {
}
