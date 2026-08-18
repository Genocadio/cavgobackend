package com.gocavgo.delivary.dto.location.input;

import com.gocavgo.delivary.enums.delivery.LocationType;
import jakarta.validation.constraints.NotNull;

public record CreateLocationInput(
        @NotNull LocationType type,
        @NotNull Double latitude,
        @NotNull Double longitude,
        String placeName,
        String placeId
) {
}
