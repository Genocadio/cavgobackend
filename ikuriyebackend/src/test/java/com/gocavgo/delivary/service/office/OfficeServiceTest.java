package com.gocavgo.delivary.service.office;

import com.gocavgo.delivary.dto.office.input.CreateOfficeInput;
import com.gocavgo.delivary.dto.office.input.UpdateOfficeInput;
import com.gocavgo.delivary.entity.office.OfficeEntity;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.repository.office.OfficeJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficeServiceTest {

    private OfficeJpaRepository officeRepo;
    private PackageLocationJpaRepository locationRepo;
    private OfficeService officeService;

    private final UUID locationId = UUID.randomUUID();
    private final UUID officeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        officeRepo = mock(OfficeJpaRepository.class);
        locationRepo = mock(PackageLocationJpaRepository.class);
        officeService = new OfficeService(officeRepo, locationRepo);
    }

    @Test
    void createOfficeWithUnknownLocationThrows() {
        when(locationRepo.existsById(locationId)).thenReturn(false);

        var input = new CreateOfficeInput("Main Branch", "123-456", locationId);

        var ex = assertThrows(RuntimeException.class, () -> officeService.createOffice(input));
        assertEquals("Location not found: " + locationId, ex.getMessage());
        verify(officeRepo, never()).save(any());
    }

    @Test
    void createOfficeWithoutLocationIsAllowed() {
        when(officeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new CreateOfficeInput("Main Branch", "123-456", null);
        var response = officeService.createOffice(input);

        assertEquals("Main Branch", response.name());
        assertEquals("123-456", response.contact());
        assertNull(response.locationId());
    }

    @Test
    void createOfficeWithLocationSavesLink() {
        when(locationRepo.existsById(locationId)).thenReturn(true);
        when(officeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new CreateOfficeInput("Main Branch", null, locationId);
        var response = officeService.createOffice(input);

        assertEquals(locationId, response.locationId());
    }

    @Test
    void updateOfficeUnknownOfficeThrows() {
        when(officeRepo.findById(officeId)).thenReturn(Optional.empty());

        var input = new UpdateOfficeInput(officeId, "New Name", null, null);

        assertThrows(RuntimeException.class, () -> officeService.updateOffice(input));
    }

    @Test
    void updateOfficeWithUnknownLocationThrows() {
        var existing = OfficeEntity.builder()
                .id(officeId)
                .name("Old Name")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(officeRepo.findById(officeId)).thenReturn(Optional.of(existing));
        when(locationRepo.existsById(locationId)).thenReturn(false);

        var input = new UpdateOfficeInput(officeId, "New Name", null, locationId);

        assertThrows(RuntimeException.class, () -> officeService.updateOffice(input));
    }

    @Test
    void updateOfficeChangesName() {
        var existing = OfficeEntity.builder()
                .id(officeId)
                .name("Old Name")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(officeRepo.findById(officeId)).thenReturn(Optional.of(existing));
        when(officeRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new UpdateOfficeInput(officeId, "New Name", null, null);
        var response = officeService.updateOffice(input);

        assertEquals("New Name", response.name());
    }
}
