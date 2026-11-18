package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyAdminManagementServiceTest {

    @Mock private CompanyUserRepository companyUserRepository;
    @Mock private VehicleAssignmentRepository assignmentRepository;
    @Mock private NexxauthClient nexxauthClient;
    @Mock private VehicleService vehicleService;

    @InjectMocks private CompanyAdminManagementService service;

    @Test
    void roleChangesPreserveNonCompanyNexxauthRoles() {
        CompanyUser member = member(20L, 7L, CompanyUserRole.WORKER);
        when(companyUserRepository.findById(20L)).thenReturn(Optional.of(member));
        when(nexxauthClient.getUser(20L)).thenReturn(identity(20L, List.of("customer", "worker")));
        when(companyUserRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateRole(7L, 10L, 20L, CompanyUserRole.DRIVER);

        verify(nexxauthClient).updateUserRoles(20L, List.of("customer", "driver"));
        assertEquals(CompanyUserRole.DRIVER, member.getRole());
    }

    @Test
    void adminsCannotGrantAdminRoleThroughOrdinaryRoleEditor() {
        when(companyUserRepository.findById(20L))
                .thenReturn(Optional.of(member(20L, 7L, CompanyUserRole.WORKER)));

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRole(7L, 10L, 20L, CompanyUserRole.ADMIN));
        verifyNoInteractions(nexxauthClient, vehicleService);
    }

    @Test
    void managementRejectsMembersFromAnotherCompany() {
        when(companyUserRepository.findById(20L))
                .thenReturn(Optional.of(member(20L, 8L, CompanyUserRole.WORKER)));

        assertThrows(AccessDeniedException.class, () -> service.removeFromCompany(7L, 10L, 20L));
        verifyNoInteractions(nexxauthClient, vehicleService);
    }

    @Test
    void removingCompanyMembershipPreservesNonCompanyRolesAndDetachesMembership() {
        CompanyUser member = member(20L, 7L, CompanyUserRole.WORKER);
        when(companyUserRepository.findById(20L)).thenReturn(Optional.of(member));
        when(nexxauthClient.getUser(20L)).thenReturn(identity(20L, List.of("customer", "worker")));

        service.removeFromCompany(7L, 10L, 20L);

        verify(nexxauthClient).updateUserRoles(20L, List.of("customer"));
        verify(companyUserRepository).save(member);
        assertNull(member.getCompany());
        assertNull(member.getRole());
    }

    private CompanyUser member(Long userId, Long companyId, CompanyUserRole role) {
        Company company = new Company();
        company.setId(companyId);
        CompanyUser user = new CompanyUser();
        user.setId(userId);
        user.setCompany(company);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private NexxauthClient.OrgUser identity(Long userId, List<String> roles) {
        return new NexxauthClient.OrgUser(userId, "Ava", "Moyo", "ava@example.com",
                "0770000000", "ava", true, roles, List.of());
    }
}
