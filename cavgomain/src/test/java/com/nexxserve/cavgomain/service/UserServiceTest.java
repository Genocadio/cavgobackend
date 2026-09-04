package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.security.NexxauthRoles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private CompanyUserRepository companyUserRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private NexxauthClient nexxauthClient;

    @InjectMocks
    private UserService userService;

    private NexxauthClient.OrgUser mockNexxauthUser(Long id, boolean enabled, List<String> roles) {
        return new NexxauthClient.OrgUser(
                id, "John", "Doe", "john@test.com", "+1234567890",
                "johndoe", enabled, roles, List.of("password")
        );
    }

    @Test
    void syncUser_createsNewUser_whenNotExists() {
        var nexxauthUser = mockNexxauthUser(100L, true, List.of("admin", "driver"));
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);
        when(companyUserRepository.findById(100L)).thenReturn(Optional.empty()).thenReturn(Optional.empty());

        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");
        when(companyRepository.findAll()).thenReturn(List.of(company));

        CompanyUser saved = new CompanyUser();
        saved.setId(100L);
        saved.setCompany(company);
        saved.setFirstName("John");
        saved.setLastName("Doe");
        saved.setRole(CompanyUserRole.ADMIN);
        saved.setStatus(UserStatus.ACTIVE);
        saved.setCreatedAt(LocalDateTime.now());
        saved.setUpdatedAt(LocalDateTime.now());
        when(companyUserRepository.save(any(CompanyUser.class))).thenReturn(saved);

        var result = userService.syncUser(100L);

        assertEquals(100L, result.getId());
        assertEquals("John", result.getFirstName());
        assertEquals(CompanyUserRole.ADMIN, result.getRole());
        assertEquals(UserStatus.ACTIVE, result.getStatus());
        verify(companyUserRepository).save(any(CompanyUser.class));
    }

    @Test
    void syncUser_updatesExistingUser_whenFieldsChanged() {
        var nexxauthUser = mockNexxauthUser(100L, true, List.of("driver"));
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);

        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");

        CompanyUser existing = new CompanyUser();
        existing.setId(100L);
        existing.setCompany(company);
        existing.setFirstName("Old");
        existing.setLastName("Name");
        existing.setRole(CompanyUserRole.DRIVER);
        existing.setStatus(UserStatus.ACTIVE);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        // Return existing with no dataHash so inline sync triggers
        when(companyUserRepository.findById(100L)).thenReturn(Optional.of(existing));
        when(companyUserRepository.save(any(CompanyUser.class))).thenAnswer(inv -> {
            CompanyUser u = inv.getArgument(0);
            u.setUpdatedAt(LocalDateTime.now());
            return u;
        });

        var result = userService.syncUser(100L);

        assertEquals("John", result.getFirstName());
        assertEquals("Doe", result.getLastName());
        verify(companyUserRepository).save(any(CompanyUser.class));
    }

    @Test
    void syncUser_noChanges_skipsSave() {
        // Use the same role that Nexxauth returns so no change is detected
        var nexxauthUser = mockNexxauthUser(100L, true, List.of("driver"));
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);

        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");

        CompanyUser existing = new CompanyUser();
        existing.setId(100L);
        existing.setCompany(company);
        existing.setFirstName("John");
        existing.setLastName("Doe");
        existing.setEmail("john@test.com");
        existing.setPhone("+1234567890");
        existing.setRole(CompanyUserRole.DRIVER);
        existing.setStatus(UserStatus.ACTIVE);
        existing.setDataHash("test-hash-123");
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        // Return with matching dataHash so inline sync skips the Nexxauth call
        when(companyUserRepository.findById(100L)).thenReturn(Optional.of(existing));

        var result = userService.syncUser(100L);

        assertEquals("John", result.getFirstName());
        verify(companyUserRepository, never()).save(any());
    }

    @Test
    void syncUser_disabledUser_setsInactive() {
        var nexxauthUser = mockNexxauthUser(100L, false, List.of("driver"));
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);

        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");

        CompanyUser existing = new CompanyUser();
        existing.setId(100L);
        existing.setCompany(company);
        existing.setFirstName("John");
        existing.setLastName("Doe");
        existing.setRole(CompanyUserRole.DRIVER);
        existing.setStatus(UserStatus.ACTIVE);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        // Return with no dataHash so inline sync triggers
        when(companyUserRepository.findById(100L)).thenReturn(Optional.of(existing));
        when(companyUserRepository.save(any(CompanyUser.class))).thenAnswer(inv -> {
            CompanyUser u = inv.getArgument(0);
            u.setUpdatedAt(LocalDateTime.now());
            return u;
        });

        var result = userService.syncUser(100L);

        assertEquals(UserStatus.INACTIVE, result.getStatus());
    }

    @Test
    void syncUser_unknownRole_defaultsToDriver() {
        var nexxauthUser = new NexxauthClient.OrgUser(
                100L, "Jane", "Smith", "jane@test.com", "+0987654321",
                "janesmith", true, List.of("unknown_role"), List.of()
        );
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);
        when(companyUserRepository.findById(100L)).thenReturn(Optional.empty()).thenReturn(Optional.empty());

        Company company = new Company();
        company.setId(1L);
        when(companyRepository.findAll()).thenReturn(List.of(company));

        CompanyUser saved = new CompanyUser();
        saved.setId(100L);
        saved.setCompany(company);
        saved.setRole(CompanyUserRole.DRIVER);
        saved.setStatus(UserStatus.ACTIVE);
        saved.setCreatedAt(LocalDateTime.now());
        saved.setUpdatedAt(LocalDateTime.now());
        when(companyUserRepository.save(any(CompanyUser.class))).thenReturn(saved);

        var result = userService.syncUser(100L);

        assertEquals(CompanyUserRole.DRIVER, result.getRole());
    }

    @Test
    void syncUser_dataHashMismatch_triggersNexxauthCall() {
        var nexxauthUser = mockNexxauthUser(100L, true, List.of("driver"));
        when(nexxauthClient.getUser(100L)).thenReturn(nexxauthUser);

        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");

        CompanyUser existing = new CompanyUser();
        existing.setId(100L);
        existing.setCompany(company);
        existing.setFirstName("John");
        existing.setLastName("Doe");
        existing.setRole(CompanyUserRole.DRIVER);
        existing.setStatus(UserStatus.ACTIVE);
        existing.setDataHash("old-hash");
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        when(companyUserRepository.findById(100L)).thenReturn(Optional.of(existing));
        when(companyUserRepository.save(any(CompanyUser.class))).thenAnswer(inv -> {
            CompanyUser u = inv.getArgument(0);
            u.setUpdatedAt(LocalDateTime.now());
            return u;
        });

        // Pass a different dataHash — should trigger sync
        var result = userService.syncUser(100L, "new-hash-456");

        // Nexxauth should have been called because hash mismatch
        verify(nexxauthClient).getUser(100L);
        // User should be synced with updated fields
        assertEquals("John", result.getFirstName());
    }

    @Test
    void syncUser_dataHashMatches_skipsNexxauthCall() {
        Company company = new Company();
        company.setId(1L);
        company.setCompanyName("Test Co");

        CompanyUser existing = new CompanyUser();
        existing.setId(100L);
        existing.setCompany(company);
        existing.setFirstName("John");
        existing.setLastName("Doe");
        existing.setRole(CompanyUserRole.DRIVER);
        existing.setStatus(UserStatus.ACTIVE);
        existing.setDataHash("same-hash");
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        when(companyUserRepository.findById(100L)).thenReturn(Optional.of(existing));

        // Pass the same dataHash — should skip Nexxauth call entirely
        var result = userService.syncUser(100L, "same-hash");

        verify(nexxauthClient, never()).getUser(any());
        assertEquals("John", result.getFirstName());
    }

    @Test
    void nexxauthRoles_mappingCoversAllRoles() {
        for (CompanyUserRole role : CompanyUserRole.values()) {
            String nexxauthName = NexxauthRoles.toNexxauthName(role);
            assertNotNull(nexxauthName, "toNexxauthName should not return null for " + role);
            assertEquals(role, NexxauthRoles.fromNexxauthName(nexxauthName),
                    "Round-trip should work for " + role);
        }
    }
}
