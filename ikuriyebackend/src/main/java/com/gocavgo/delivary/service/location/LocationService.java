package com.gocavgo.delivary.service.location;

import com.gocavgo.delivary.dto.delivery.output.LocationResponse;
import com.gocavgo.delivary.dto.location.input.CreateLocationInput;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocationService {

    private final PackageLocationJpaRepository locationRepo;

    /**
     * Creates a standalone package_locations row (packageId = null) that an
     * office can reference via its locationId.
     */
    @Transactional
    public LocationResponse createStandaloneLocation(CreateLocationInput input) {
        var location = PackageLocationEntity.builder()
                .type(input.type())
                .latitude(input.latitude())
                .longitude(input.longitude())
                .placeName(input.placeName())
                .placeId(input.placeId())
                .build();
        return toResponse(locationRepo.save(location));
    }

    /**
     * Null-safe lookup for schema mapping: a dangling locationId (e.g. the
     * location row was deleted) degrades to null instead of failing the query.
     */
    @Transactional(readOnly = true)
    public LocationResponse getLocationByIdOrNull(UUID id) {
        return locationRepo.findById(id).map(this::toResponse).orElse(null);
    }

    private LocationResponse toResponse(PackageLocationEntity entity) {
        return new LocationResponse(
                entity.getId(),
                entity.getType(),
                entity.getLatitude(),
                entity.getLongitude(),
                entity.getPlaceName(),
                entity.getPlaceId(),
                entity.getOfficeId()
        );
    }
}
