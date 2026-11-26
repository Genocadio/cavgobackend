package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record ClaimOfficePackagesInput(
        @NotEmpty List<UUID> packageIds,
        /** Optional override; defaults to the authenticated worker's office. */
        UUID officeLocationId,
        String notes
) {
}
