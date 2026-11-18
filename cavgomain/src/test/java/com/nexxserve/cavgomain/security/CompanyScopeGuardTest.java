package com.nexxserve.cavgomain.security;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyAccessRequest;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.DriverRequest;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyAccessRequestRepository;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.DriverRequestRepository;
import com.nexxserve.cavgomain.repository.VehicleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyScopeGuardTest {

    @Mock private CompanyUserRepository companyUserRepository;
    @Mock private CompanyRepository companyRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private DriverRequestRepository driverRequestRepository;
    @Mock private CompanyAccessRequestRepository companyAccessRequestRepository;

    @InjectMocks private CompanyScopeGuard guard;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void globalAdminCanAccessAnyCompanyWithoutLocalMembership() {
        authenticate("1", CompanyUserRole.ADMIN);

        assertEquals(99L, guard.requireCompanyScope(99L));

        verifyNoInteractions(companyUserRepository, companyRepository, vehicleRepository,
                driverRequestRepository, companyAccessRequestRepository);
    }

    @Test
    void fleetManagerCanAccessOnlyApprovedCompany() {
        authenticate("7", CompanyUserRole.FLEET_MANAGER);
        when(companyUserRepository.findById(7L)).thenReturn(Optional.of(membership(1L, CompanyUserRole.FLEET_MANAGER)));

        assertEquals(1L, guard.requireCompanyScope(1L));
        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScope(2L));
    }

    @Test
    void scopedRoleWithoutApprovedMembershipIsDenied() {
        authenticate("7", CompanyUserRole.FLEET_MANAGER);
        when(companyUserRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScope(1L));
    }

    @Test
    void unrecognisedTokenRoleIsDeniedEvenWithCompanyMembership() {
        authenticate("7");

        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScope(1L));
    }

    @Test
    void vehicleFromAnotherCompanyIsDenied() {
        authenticate("7", CompanyUserRole.FLEET_MANAGER);
        when(companyUserRepository.findById(7L)).thenReturn(Optional.of(membership(1L, CompanyUserRole.FLEET_MANAGER)));
        Vehicle vehicle = new Vehicle();
        vehicle.setCompany(company(2L));
        when(vehicleRepository.findById(10L)).thenReturn(Optional.of(vehicle));

        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScopeForVehicle(10L));
    }

    @Test
    void driverRequestFromAnotherCompanyIsDenied() {
        authenticate("7", CompanyUserRole.FLEET_MANAGER);
        when(companyUserRepository.findById(7L)).thenReturn(Optional.of(membership(1L, CompanyUserRole.FLEET_MANAGER)));
        DriverRequest request = new DriverRequest();
        request.setCompany(company(2L));
        when(driverRequestRepository.findById(11L)).thenReturn(Optional.of(request));

        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScopeForDriverRequest(11L));
    }

    @Test
    void companyAccessRequestFromAnotherCompanyIsDenied() {
        authenticate("7", CompanyUserRole.FLEET_MANAGER);
        when(companyUserRepository.findById(7L)).thenReturn(Optional.of(membership(1L, CompanyUserRole.FLEET_MANAGER)));
        CompanyAccessRequest request = new CompanyAccessRequest();
        request.setCompany(company(2L));
        when(companyAccessRequestRepository.findById(12L)).thenReturn(Optional.of(request));

        assertThrows(AccessDeniedException.class, () -> guard.requireCompanyScopeForCompanyAccessRequest(12L));
    }

    private void authenticate(String principal, CompanyUserRole... roles) {
        var authorities = Arrays.stream(roles)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "token", authorities));
    }

    private CompanyUser membership(Long companyId, CompanyUserRole role) {
        CompanyUser user = new CompanyUser();
        user.setId(7L);
        user.setCompany(company(companyId));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private Company company(Long id) {
        Company company = new Company();
        company.setId(id);
        return company;
    }
}
