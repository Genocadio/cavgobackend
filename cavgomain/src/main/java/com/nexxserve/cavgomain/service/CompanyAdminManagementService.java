package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.security.NexxauthRoles;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class CompanyAdminManagementService {

    private static final Set<String> COMPANY_ROLE_NAMES =
            Set.of("admin", "supervisor", "fleet_manager", "driver", "worker");
    private static final Set<CompanyUserRole> ADMIN_ASSIGNABLE_ROLES =
            Set.of(CompanyUserRole.FLEET_MANAGER, CompanyUserRole.DRIVER, CompanyUserRole.WORKER);

    private final CompanyUserRepository companyUserRepository;
    private final VehicleAssignmentRepository assignmentRepository;
    private final NexxauthClient nexxauthClient;
    private final VehicleService vehicleService;

    @Transactional(readOnly = true)
    public List<CompanyUserResponseDto> listStaff(Long companyId) {
        return companyUserRepository.findByCompanyId(companyId).stream()
                .map(user -> {
                    CompanyUserResponseDto dto = CompanyUserResponseDto.fromEntity(user);
                    if (user.getRole() == CompanyUserRole.DRIVER) {
                        assignmentRepository.findActiveAssignmentsByDriver(user.getId()).stream()
                                .filter(assignment -> assignment.getVehicle().getCompany() != null
                                        && assignment.getVehicle().getCompany().getId().equals(companyId))
                                .findFirst()
                                .ifPresent(assignment -> dto.setVehicle(
                                        com.nexxserve.cavgomain.dto.response.VehicleResponseDto
                                                .fromEntity(assignment.getVehicle(), null)));
                    }
                    return dto;
                })
                .toList();
    }

    public CompanyUserResponseDto updateRole(
            Long companyId, Long callerId, Long staffId, CompanyUserRole newRole) {
        return updateRole(companyId, callerId, staffId, newRole, false);
    }

    /**
     * @param superuser the global SUPER_ADMIN may assign any company role
     *                  (including ADMIN/SUPERVISOR) and is not bound by the
     *                  ordinary company admin's assignment restrictions.
     */
    public CompanyUserResponseDto updateRole(
            Long companyId, Long callerId, Long staffId, CompanyUserRole newRole, boolean superuser) {
        if (newRole == CompanyUserRole.SUPER_ADMIN) {
            throw new IllegalArgumentException("SUPER_ADMIN is a global role and cannot be a company membership");
        }
        CompanyUser user = requireCompanyUser(companyId, staffId);
        ensureNotSelf(callerId, staffId);
        if (!superuser && (user.getRole() == CompanyUserRole.SUPERVISOR
                || (newRole != null && !ADMIN_ASSIGNABLE_ROLES.contains(newRole)))) {
            throw new IllegalArgumentException("Company admins can assign worker, driver, or fleet manager roles only");
        }
        if (user.getRole() == newRole) {
            return CompanyUserResponseDto.fromEntity(user);
        }
        if (!superuser && user.getRole() == CompanyUserRole.ADMIN
                && companyUserRepository.countByCompanyIdAndRole(companyId, CompanyUserRole.ADMIN) <= 1) {
            throw new IllegalArgumentException("A company must have at least one administrator");
        }

        var identity = nexxauthClient.getUser(staffId);
        List<String> roles = nonCompanyRoles(identity.roles());
        roles.add(NexxauthRoles.toNexxauthName(newRole));

        if (user.getRole() == CompanyUserRole.DRIVER && newRole != CompanyUserRole.DRIVER) {
            vehicleService.unassignDriverFromCompany(staffId, companyId);
        }
        nexxauthClient.updateUserRoles(staffId, roles);
        user.setRole(newRole);
        return CompanyUserResponseDto.fromEntity(companyUserRepository.save(user));
    }

    public void removeFromCompany(Long companyId, Long callerId, Long staffId) {
        removeFromCompany(companyId, callerId, staffId, false);
    }

    public void removeFromCompany(Long companyId, Long callerId, Long staffId, boolean superuser) {
        CompanyUser user = requireCompanyUser(companyId, staffId);
        ensureNotSelf(callerId, staffId);
        if (!superuser && user.getRole() == CompanyUserRole.SUPERVISOR) {
            throw new IllegalArgumentException("A company administrator cannot remove a supervisor");
        }
        if (!superuser && user.getRole() == CompanyUserRole.ADMIN
                && companyUserRepository.countByCompanyIdAndRole(companyId, CompanyUserRole.ADMIN) <= 1) {
            throw new IllegalArgumentException("A company must have at least one administrator");
        }

        var identity = nexxauthClient.getUser(staffId);
        nexxauthClient.updateUserRoles(staffId, nonCompanyRoles(identity.roles()));
        if (user.getRole() == CompanyUserRole.DRIVER) {
            vehicleService.unassignDriverFromCompany(staffId, companyId);
        }
        user.setCompany(null);
        user.setOffice(null);
        user.setRole(null);
        companyUserRepository.save(user);
    }

    public void unassignDriver(Long companyId, Long staffId) {
        CompanyUser user = requireCompanyUser(companyId, staffId);
        if (user.getRole() != CompanyUserRole.DRIVER) {
            throw new IllegalArgumentException("The selected company user is not a driver");
        }
        vehicleService.unassignDriverFromCompany(staffId, companyId);
    }

    private CompanyUser requireCompanyUser(Long companyId, Long staffId) {
        CompanyUser user = companyUserRepository.findById(staffId)
                .orElseThrow(() -> new AccessDeniedException("Company access denied"));
        if (user.getCompany() == null || !user.getCompany().getId().equals(companyId)) {
            throw new AccessDeniedException("Company access denied");
        }
        return user;
    }

    private void ensureNotSelf(Long callerId, Long staffId) {
        if (callerId == null || callerId.equals(staffId)) {
            throw new IllegalArgumentException("You cannot change your own company administrator access");
        }
    }

    private List<String> nonCompanyRoles(List<String> existingRoles) {
        List<String> roles = new ArrayList<>();
        if (existingRoles != null) {
            existingRoles.stream()
                    .filter(role -> role != null
                            && !COMPANY_ROLE_NAMES.contains(role.trim().toLowerCase(Locale.ROOT)))
                    .forEach(roles::add);
        }
        return roles;
    }
}
