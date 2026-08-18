package com.gocavgo.delivary.dto.office.input;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateOfficeInput(
        @NotNull UUID officeId,
        @Size(max = 255) String name,
        @Size(max = 255) String contact,
        UUID locationId
) {
}
