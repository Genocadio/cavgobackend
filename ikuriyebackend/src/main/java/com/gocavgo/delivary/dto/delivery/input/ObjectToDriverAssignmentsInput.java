package com.gocavgo.delivary.dto.delivery.input;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record ObjectToDriverAssignmentsInput(
        @NotEmpty List<UUID> packageIds,
        String reason
) {
}
