package com.gocavgo.delivary.dto.delivery.output;

import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.dto.transfer.output.TransferResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PackageResponse(
        UUID id,
        String trackingCode,
        DeliveryType deliveryType,
        PackageStatus status,
        Long creatorId,
        UUID companyId,
        UUID tripId,
        List<CustodianResponse> custodians,
        List<PersonResponse> people,
        List<LocationResponse> locations,
        DetailResponse details,
        List<EventResponse> events,
        List<CustodyResponse> custody,
        List<TransferResponse> transfers,
        Instant createdAt,
        Instant updatedAt
) {
    public record CustodianResponse(
            UUID id,
            Long userId,
            String name,
            String phone,
            CustodianRole role,
            Instant assignedAt
    ) {
    }

    public record PersonResponse(
            UUID id,
            PersonRole role,
            Long userId,
            String name,
            String phone
    ) {
    }

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

    public record DetailResponse(
            String category,
            String description,
            boolean fragile,
            Double weight,
            Double length,
            Double width,
            Double height,
            Double declaredValue,
            List<MediaResponse> media
    ) {
    }

    public record MediaResponse(
            UUID id,
            String url,
            String mimeType
    ) {
    }

    public record EventResponse(
            UUID id,
            String eventType,
            Long actorId,
            String description,
            Instant createdAt
    ) {
    }

    public record CustodyResponse(
            UUID id,
            String fromEntity,
            String toEntity,
            Instant timestamp,
            String notes
    ) {
    }
}
