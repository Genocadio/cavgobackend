package com.gocavgo.delivary.service.office;

import com.gocavgo.delivary.dto.office.input.CreateOfficeInput;
import com.gocavgo.delivary.dto.office.input.UpdateOfficeInput;
import com.gocavgo.delivary.dto.office.output.OfficeResponse;
import com.gocavgo.delivary.entity.office.OfficeEntity;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.repository.office.OfficeJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OfficeService {

    private final OfficeJpaRepository officeRepo;
    private final PackageLocationJpaRepository locationRepo;

    @Transactional
    public OfficeResponse createOffice(CreateOfficeInput input) {
        validateLocation(input.locationId());
        var now = Instant.now();
        var office = OfficeEntity.builder()
                .name(input.name().strip())
                .contact(input.contact())
                .locationId(input.locationId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return toResponse(officeRepo.save(office));
    }

    @Transactional
    public OfficeResponse updateOffice(UpdateOfficeInput input) {
        var office = officeRepo.findById(input.officeId())
                .orElseThrow(() -> new RuntimeException("Office not found: " + input.officeId()));
        if (input.name() != null && !input.name().isBlank()) {
            office.setName(input.name().strip());
        }
        if (input.contact() != null) {
            office.setContact(input.contact());
        }
        if (input.locationId() != null) {
            validateLocation(input.locationId());
            office.setLocationId(input.locationId());
        }
        return toResponse(officeRepo.save(office));
    }

    @Transactional(readOnly = true)
    public OfficeResponse getOfficeById(UUID id) {
        return officeRepo.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new RuntimeException("Office not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<OfficeResponse> getAllOffices() {
        return officeRepo.findAll().stream().map(this::toResponse).toList();
    }

    /**
     * Throws if the given location id does not exist. Null is allowed — an
     * office may be created before its standalone location row.
     */
    private void validateLocation(UUID locationId) {
        if (locationId != null && !locationRepo.existsById(locationId)) {
            throw new RuntimeException("Location not found: " + locationId);
        }
    }

    private OfficeResponse toResponse(OfficeEntity office) {
        return new OfficeResponse(
                office.getId(),
                office.getName(),
                office.getContact(),
                office.getLocationId(),
                office.getCreatedAt(),
                office.getUpdatedAt()
        );
    }
}
