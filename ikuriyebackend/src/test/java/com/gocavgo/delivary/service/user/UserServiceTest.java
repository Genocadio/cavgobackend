package com.gocavgo.delivary.service.user;

import com.gocavgo.delivary.dto.user.input.AssignRoleInput;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.DriverType;
import com.gocavgo.delivary.enums.user.EmploymentType;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private WorkerProfileRepository workerProfileRepository;
    private DriverProfileRepository driverProfileRepository;
    private NexxauthClient nexxauthClient;
    private UserService userService;

    private final Long userId = 42L;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        workerProfileRepository = mock(WorkerProfileRepository.class);
        driverProfileRepository = mock(DriverProfileRepository.class);
        var userMapper = mock(UserMapper.class);
        nexxauthClient = mock(NexxauthClient.class);
        var storageService = mock(StorageService.class);
        var mediaRepo = mock(PackageMediaJpaRepository.class);

        when(userMapper.toResponse(any(), any())).thenAnswer(invocation -> {
            var u = invocation.getArgument(0, UserEntity.class);
            var role = invocation.getArgument(1, com.gocavgo.delivary.enums.user.Role.class);
            return new UserResponse(u.getId(), u.getEmail(), u.getPhone(), u.getFirstName(),
                    u.getLastName(), u.getUsername(), null, role, u.getStatus(),
                    u.getDataHash(), u.getCreatedAt(), u.getUpdatedAt(), null);
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
        when(driverProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.CUSTOMER, null);
        userService.assignRole(input);

        verify(workerProfileRepository).deleteById(profile.getId());
    }

    // ── Driver profile tests ─────────────────────────────────────────────

    @Test
    void assignDriverRoleCreatesProfileWhenNoneExists() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(driverProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(driverProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(workerProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.DRIVER, null);
        userService.assignRole(input);

        var captor = ArgumentCaptor.forClass(DriverProfileEntity.class);
        verify(driverProfileRepository).save(captor.capture());
        var captured = captor.getValue();
        assertEquals(userId, captured.getUser().getId());
        assertEquals(EmploymentType.COMPANY, captured.getEmploymentType());
        assertEquals(DriverType.OPEN, captured.getDriverType());
        assertEquals(DriverStatus.ONLINE, captured.getStatus());
    }

    @Test
    void leavingDriverRoleDeletesProfile() {
        var profile = DriverProfileEntity.builder()
                .id(UUID.randomUUID())
                .user(workerUser())
                .employmentType(EmploymentType.COMPANY)
                .driverType(DriverType.OPEN)
                .status(DriverStatus.OFFLINE)
                .createdAt(Instant.now())
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(workerUser()));
        when(driverProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var input = new AssignRoleInput(userId, Role.CUSTOMER, null);
        userService.assignRole(input);

        verify(driverProfileRepository).deleteById(profile.getId());
    }

    // ── searchUsers role filtering ──────────────────────────────────────────

    private UserEntity userEntity(Long id, String email) {
        return UserEntity.builder()
                .id(id)
                .email(email)
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private NexxauthClient.OrgUser orgUser(Long id, String... roles) {
        return new NexxauthClient.OrgUser(id, "First", "Last", id + "@test.com",
                null, null, true, List.of(roles), List.of());
    }

    @Test
    void searchUsersWithRoleFilterReturnsOnlyMatchingRole() {
        var driver = userEntity(1L, "driver@test.com");
        var worker = userEntity(2L, "worker@test.com");
        when(userRepository.searchUsers(null)).thenReturn(List.of(driver, worker));
        when(nexxauthClient.getUser(1L)).thenReturn(orgUser(1L, "DRIVER"));
        when(nexxauthClient.getUser(2L)).thenReturn(orgUser(2L, "WORKER"));
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(driverProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var results = userService.searchUsers(null, Role.DRIVER, null);

        assertEquals(1, results.size());
        assertEquals(Role.DRIVER, results.get(0).role());
        assertEquals(1L, results.get(0).id());
    }

    @Test
    void searchUsersWithNullRoleReturnsAllUsers() {
        var driver = userEntity(1L, "driver@test.com");
        var worker = userEntity(2L, "worker@test.com");
        var customer = userEntity(3L, "customer@test.com");
        when(userRepository.searchUsers(null)).thenReturn(List.of(driver, worker, customer));
        when(nexxauthClient.getUser(1L)).thenReturn(orgUser(1L, "DRIVER"));
        when(nexxauthClient.getUser(2L)).thenReturn(orgUser(2L, "WORKER"));
        when(nexxauthClient.getUser(3L)).thenReturn(orgUser(3L, "CUSTOMER"));
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(driverProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var results = userService.searchUsers(null, null, null);

        assertEquals(3, results.size());
    }

    @Test
    void searchUsersWithQueryAndRoleFiltersBoth() {
        var driver1 = userEntity(1L, "alice@test.com");
        var driver2 = userEntity(2L, "bob@test.com");
        var worker = userEntity(3L, "alice-worker@test.com");
        when(userRepository.searchUsers("alice")).thenReturn(List.of(driver1, worker));
        when(nexxauthClient.getUser(1L)).thenReturn(orgUser(1L, "DRIVER"));
        when(nexxauthClient.getUser(3L)).thenReturn(orgUser(3L, "WORKER"));
        when(driverProfileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(driverProfileRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var results = userService.searchUsers("alice", Role.DRIVER, null);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).id());
    }
}
