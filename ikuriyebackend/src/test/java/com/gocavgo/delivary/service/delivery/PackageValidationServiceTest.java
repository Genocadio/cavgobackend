package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PackageValidationServiceTest {

    private UserRepository userRepository;
    private PackageValidationService validationService;

    private final Long userId = 42L;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        validationService = new PackageValidationService(
                userRepository,
                mock(DriverProfileRepository.class)
        );
    }

    private UserEntity activeUser() {
        return UserEntity.builder()
                .id(userId)
                .email("user@example.com")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void validateCreatorAcceptsActiveWorker() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        assertDoesNotThrow(() -> validationService.validateCreator(userId, Role.WORKER));
    }

    @Test
    void validateCreatorRejectsNonCreatorRole() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser()));
        var ex = assertThrows(RuntimeException.class,
                () -> validationService.validateCreator(userId, Role.ADMIN));
        assertEquals("User role cannot create packages: ADMIN", ex.getMessage());
    }

    @Test
    void validateTransitionAllowsFixedRouteOriginOffice() {
        assertDoesNotThrow(() ->
                validationService.validateTransition(PackageStatus.CREATED, PackageStatus.ORIGIN_OFFICE, DeliveryType.FIXED_ROUTE));
    }

    @Test
    void validateTransitionAllowsOriginOfficeToInTransit() {
        // Drivers who accepted a transfer at the origin office can drive directly
        assertDoesNotThrow(() ->
                validationService.validateTransition(PackageStatus.ORIGIN_OFFICE, PackageStatus.IN_TRANSIT, DeliveryType.FIXED_ROUTE));
    }

    @Test
    void validateTransitionAllowsAcceptedToAssignedDriver() {
        // Driver-created FIXED_ROUTE packages start at ACCEPTED and can be assigned
        assertDoesNotThrow(() ->
                validationService.validateTransition(PackageStatus.ACCEPTED, PackageStatus.ASSIGNED_DRIVER, DeliveryType.FIXED_ROUTE));
    }

    @Test
    void validateTransitionAllowsAcceptedToInTransit() {
        // Driver-created FIXED_ROUTE packages can go directly to IN_TRANSIT (driver picks up)
        assertDoesNotThrow(() ->
                validationService.validateTransition(PackageStatus.ACCEPTED, PackageStatus.IN_TRANSIT, DeliveryType.FIXED_ROUTE));
    }

    @Test
    void validateTransitionRejectsInvalidMove() {
        var ex = assertThrows(RuntimeException.class, () ->
                validationService.validateTransition(PackageStatus.IN_TRANSIT, PackageStatus.ASSIGNED_DRIVER, DeliveryType.FIXED_ROUTE));
        assertEquals("Invalid status transition: IN_TRANSIT -> ASSIGNED_DRIVER", ex.getMessage());
    }
}
