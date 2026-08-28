package com.gocavgo.delivary.service.user;

import com.gocavgo.delivary.dto.user.input.AssignRoleInput;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.mapper.user.UserMapper;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.repository.user.WorkerProfileRepository;
import com.gocavgo.delivary.security.NexxauthClient;
import com.gocavgo.delivary.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private WorkerProfileRepository workerProfileRepository;
    private UserService userService;

    private final Long userId = 42L;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        workerProfileRepository = mock(WorkerProfileRepository.class);
        var driverProfileRepository = mock(DriverProfileRepository.class);
        var userMapper = mock(UserMapper.class);
        var nexxauthClient = mock(NexxauthClient.class);
        var storageService = mock(StorageService.class);
        var mediaRepo = mock(PackageMediaJpaRepository.class);

        when(userMapper.toResponse(any(), any())).thenAnswer(invocation -> {
            var u = invocation.getArgument(0, UserEntity.class);
            var role = invocation.getArgument(1, com.gocavgo.delivary.enums.user.Role.class);
            return new UserResponse(u.getId(), u.getEmail(), u.getPhone(), u.getFirstName(),
                    u.getLastName(), u.getUsername(), null, role, u.getStatus(),
                    u.getCreatedAt(), u.getUpdatedAt());
        });

        userService = new UserService(userRepository, workerProfileRepository,
                driverProfileRepository, userMapper, nexxauthClient, storageService, mediaRepo);
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
    void assignWorkerRoleCreatesProfileWhenNoneExists() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(workerProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(workerProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.WORKER, null);
        userService.assignRole(input);

        var captor = ArgumentCaptor.forClass(WorkerProfileEntity.class);
        verify(workerProfileRepository).save(captor.capture());
        var captured = captor.getValue();
        assertNull(captured.getCompanyId());
        assertEquals(userId, captured.getUser().getId());
    }

    @Test
    void leavingWorkerRoleDeletesProfile() {
        var profile = WorkerProfileEntity.builder()
                .id(UUID.randomUUID())
                .user(workerUser())
                .companyId(UUID.randomUUID())
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
