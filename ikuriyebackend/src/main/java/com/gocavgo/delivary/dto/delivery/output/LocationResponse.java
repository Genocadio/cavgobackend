package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.enums.delivery.LocationType;

import java.util.UUID;

public record LocationResponse(
        UUID id,
        LocationType type,
        Double latitude,
        Double longitude,
        String placeName,
        String placeId,
        UUID officeLocationId
) {
}
