package com.gocavgo.delivary.dto.office.output;

import java.time.Instant;
import java.util.UUID;

public record OfficeResponse(
        UUID id,
        String name,
        String contact,
        UUID locationId,
        Instant createdAt,
        Instant updatedAt
) {
}
