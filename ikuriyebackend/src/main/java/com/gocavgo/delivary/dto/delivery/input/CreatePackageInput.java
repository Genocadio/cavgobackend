package com.gocavgo.delivary.dto.delivery.input;

import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.MediaType;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;

import java.util.List;
import java.util.UUID;

public record CreatePackageInput(
        DeliveryType deliveryType,
        PersonInput sender,
        PersonInput receiver,
        LocationInput origin,
        LocationInput destination,
        DetailInput details,
        TransferRuleType transferRuleType,
        UUID transferMatchCompanyId,
        Long transferMatchUserId
) {
    public record PersonInput(
            PersonRole role,
            Long userId,
            String name,
            String phone
    ) {
    }

    public record LocationInput(
            LocationType type,
            Double latitude,
            Double longitude,
            String placeName,
            String placeId,
            UUID officeLocationId
    ) {
    }

    public record DetailInput(
            String category,
            String description,
            Boolean fragile,
            Double weight,
            Double length,
            Double width,
            Double height,
            Double declaredValue,
            List<MediaInput> media
    ) {
    }

    public record MediaInput(
            String mediaId
    ) {
    }
}
