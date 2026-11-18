package com.nexxserve.cavgomain.security;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyAccessRequestRepository;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.DriverRequestRepository;
import com.nexxserve.cavgomain.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces company boundaries on company-scoped endpoints.
 *
 * <p>The verified Nexxauth token determines whether the caller is global staff
 * (ADMIN/SUPERVISOR) or a company-scoped role (FLEET_MANAGER/DRIVER/WORKER).
 * Global staff may access any company. Company-scoped callers may only access
 * the company attached to their approved local {@link CompanyUser} membership;
 * a requested role or a token role with no approved membership is never enough
 * to cross that boundary.
 */
@Component
@RequiredArgsConstructor
public class CompanyScopeGuard {

    private static final String DENIED = "Company access denied";

    private final CompanyUserRepository companyUserRepository;
    private final CompanyRepository companyRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverRequestRepository driverRequestRepository;
    private final CompanyAccessRequestRepository companyAccessRequestRepository;

    /**
     * Requires access to {@code companyId}. Returns the same id so callers can
     * reuse it in service calls.
     */
    public Long requireCompanyScope(Long companyId) {
        if (hasGlobalStaffRole()) {
            return companyId;
        }

        CompanyUser membership = requireScopedMembership();
        Long allowedCompanyId = companyIdOf(membership);
        if (companyId == null || !allowedCompanyId.equals(companyId)) {
            throw denied();
        }
        return companyId;
    }

    /** Resolves a company code to an id and then applies the scope check. */
    public Long requireCompanyScopeForCompanyCode(String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            if (hasGlobalStaffRole()) {
                return null;
            }
            throw denied();
        }
        var company = companyRepository.findByCompanyCode(companyCode.trim())
                .orElseThrow(() -> denied());
        return requireCompanyScope(company.getId());
    }

    /**
     * Uses the caller's own approved membership as the scope. Used when a
     * request does not carry a company id (for example vehicle creation where
     * the company is inferred from the user).
     */
    public Long requireCurrentCompanyScope() {
        if (hasGlobalStaffRole()) {
            return null;
        }
        return companyIdOf(requireScopedMembership());
    }

    /**
     * For global endpoints that should be narrowed for company-scoped callers.
     * Returns {@code null} for global staff (meaning no filter), otherwise the
     * caller's approved company id.
     */
    public Long requireGlobalOrDefaultCompanyScope() {
        return requireCurrentCompanyScope();
    }

    public void requireCompanyScopeForStaff(Long staffUserId) {
        if (staffUserId == null) return;
        if (hasGlobalStaffRole()) return;
        var staff = companyUserRepository.findById(staffUserId).orElseThrow(this::denied);
        requireCompanyScope(companyIdOf(staff));
    }

    public void requireCompanyScopeForVehicle(Long vehicleId) {
        if (vehicleId == null) return;
        if (hasGlobalStaffRole()) return;
        var vehicle = vehicleRepository.findById(vehicleId).orElseThrow(this::denied);
        requireCompanyScope(companyIdOf(vehicle.getCompany()));
    }

    public void requireCompanyScopeForVehiclePlate(String licensePlate) {
        if (licensePlate == null || licensePlate.isBlank()) return;
        if (hasGlobalStaffRole()) return;
        var vehicle = vehicleRepository.findByLicensePlate(licensePlate).orElseThrow(this::denied);
        requireCompanyScope(companyIdOf(vehicle.getCompany()));
    }

    public void requireCompanyScopeForDriverRequest(Long requestId) {
        if (requestId == null) return;
        if (hasGlobalStaffRole()) return;
        var request = driverRequestRepository.findById(requestId).orElseThrow(this::denied);
        requireCompanyScope(companyIdOf(request.getCompany()));
    }

    public void requireCompanyScopeForCompanyAccessRequest(Long requestId) {
        if (requestId == null) return;
        if (hasGlobalStaffRole()) return;
        var request = companyAccessRequestRepository.findById(requestId).orElseThrow(this::denied);
        requireCompanyScope(companyIdOf(request.getCompany()));
    }

    private boolean hasGlobalStaffRole() {
        Set<CompanyUserRole> roles = currentRoles();
        return roles.contains(CompanyUserRole.ADMIN) || roles.contains(CompanyUserRole.SUPERVISOR);
    }

    private CompanyUser requireScopedMembership() {
        Long userId = currentUserId();
        CompanyUser membership = companyUserRepository.findById(userId).orElseThrow(this::denied);
        if (membership.getStatus() == UserStatus.INACTIVE) {
            throw denied();
        }
        CompanyUserRole role = membership.getRole();
        if (role != CompanyUserRole.FLEET_MANAGER
                && role != CompanyUserRole.DRIVER
                && role != CompanyUserRole.WORKER) {
            throw denied();
        }
        if (companyIdOf(membership) == null) {
            throw denied();
        }
        return membership;
    }

    private Long companyIdOf(CompanyUser membership) {
        return membership.getCompany() == null ? null : membership.getCompany().getId();
    }

    private Long companyIdOf(com.nexxserve.cavgomain.entity.Company company) {
        return company == null ? null : company.getId();
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String principal)) {
            throw denied();
        }
        try {
            return Long.valueOf(principal);
        } catch (NumberFormatException e) {
            throw denied();
        }
    }

    private Set<CompanyUserRole> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw denied();
        }
        return authentication.getAuthorities().stream()
                .map(NexxauthRoles::fromAuthority)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException(DENIED);
    }
}
