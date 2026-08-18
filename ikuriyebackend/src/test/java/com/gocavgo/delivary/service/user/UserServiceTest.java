package com.gocavgo.delivary.service.user;

import com.gocavgo.delivary.dto.user.input.AssignRoleInput;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.mapper.user.UserMapper;
import com.gocavgo.delivary.repository.office.OfficeJpaRepository;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.repository.user.WorkerProfileRepository;
import com.gocavgo.delivary.security.NexxauthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private WorkerProfileRepository workerProfileRepository;
    private OfficeJpaRepository officeRepository;
    private UserService userService;

    private final Long userId = 42L;
    private final UUID officeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        workerProfileRepository = mock(WorkerProfileRepository.class);
        var driverProfileRepository = mock(DriverProfileRepository.class);
        var userMapper = mock(UserMapper.class);
        var nexxauthClient = mock(NexxauthClient.class);
        officeRepository = mock(OfficeJpaRepository.class);

        when(userMapper.toResponse(any(), any())).thenAnswer(invocation -> {
            var u = invocation.getArgument(0, UserEntity.class);
            var role = invocation.getArgument(1, com.gocavgo.delivary.enums.user.Role.class);
            return new UserResponse(u.getId(), u.getEmail(), u.getPhone(), u.getFirstName(),
                    u.getLastName(), u.getUsername(), role, u.getStatus(),
                    u.getCreatedAt(), u.getUpdatedAt());
        });

        userService = new UserService(userRepository, workerProfileRepository,
                driverProfileRepository, officeRepository, userMapper, nexxauthClient);
    }

    private UserEntity workerUser() {
        return UserEntity.builder()
                .id(userId)
                .email("worker@example.com")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void assignWorkerRoleWithoutOfficeIdThrows() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));

        var input = new AssignRoleInput(userId, Role.WORKER, null);

        var ex = assertThrows(RuntimeException.class, () -> userService.assignRole(input));
        assertEquals("officeId is required when assigning the WORKER role", ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignWorkerRoleWithUnknownOfficeThrows() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(officeRepository.existsById(officeId)).thenReturn(false);

        var input = new AssignRoleInput(userId, Role.WORKER, officeId);

        var ex = assertThrows(RuntimeException.class, () -> userService.assignRole(input));
        assertEquals("Office not found: " + officeId, ex.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void assignWorkerRoleCreatesProfileWhenNoneExists() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(officeRepository.existsById(officeId)).thenReturn(true);
        when(workerProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(workerProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.WORKER, officeId);
        userService.assignRole(input);

        var captor = ArgumentCaptor.forClass(WorkerProfileEntity.class);
        verify(workerProfileRepository).save(captor.capture());
        var captured = captor.getValue();
        assertEquals(officeId, captured.getCompanyId());
        assertEquals(userId, captured.getUser().getId());
    }

    @Test
    void assignWorkerRoleUpdatesExistingProfile() {
        var existingProfile = WorkerProfileEntity.builder()
                .id(UUID.randomUUID())
                .user(workerUser())
                .companyId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(officeRepository.existsById(officeId)).thenReturn(true);
        when(workerProfileRepository.findByUserId(userId)).thenReturn(Optional.of(existingProfile));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.WORKER, officeId);
        userService.assignRole(input);

        assertEquals(officeId, existingProfile.getCompanyId());
        verify(workerProfileRepository).save(existingProfile);
    }

    @Test
    void leavingWorkerRoleDeletesProfile() {
        var profile = WorkerProfileEntity.builder()
                .id(UUID.randomUUID())
                .user(workerUser())
                .companyId(officeId)
                .createdAt(Instant.now())
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(workerProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.CUSTOMER, null);
        userService.assignRole(input);

        verify(workerProfileRepository).deleteById(profile.getId());
    }
}
