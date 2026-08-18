package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.repository.office.OfficeJpaRepository;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackageValidationServiceTest {

    private OfficeJpaRepository officeRepository;
    private PackageValidationService validationService;

    private final UUID officeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        officeRepository = mock(OfficeJpaRepository.class);
        validationService = new PackageValidationService(
                mock(UserRepository.class),
                mock(DriverProfileRepository.class),
                officeRepository
        );
    }

    @Test
    void nullOfficeIdIsAllowed() {
        assertDoesNotThrow(() -> validationService.validateOffice(null));
    }

    @Test
    void unknownOfficeThrows() {
        when(officeRepository.existsById(officeId)).thenReturn(false);
        var ex = assertThrows(RuntimeException.class, () -> validationService.validateOffice(officeId));
        assertEquals("Office not found: " + officeId, ex.getMessage());
    }

    @Test
    void knownOfficeIsAllowed() {
        when(officeRepository.existsById(officeId)).thenReturn(true);
        assertDoesNotThrow(() -> validationService.validateOffice(officeId));
    }
}
