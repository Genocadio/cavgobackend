package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyUserRequestDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.security.CompanyScopeGuard;
import com.nexxserve.cavgomain.service.CompanyUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/main/staff")
@RequiredArgsConstructor
public class CompanyUserController {

    private final CompanyUserService companyUserService;
    private final CompanyScopeGuard companyScopeGuard;

    /**
     * Staff-only: creating a staff record writes a role (a licence number makes
     * the user a DRIVER) and a company. An ordinary authenticated user must not
     * be able to grant themselves a role this way.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public CompanyUserResponseDto createCompanyUser(@Valid @RequestBody CompanyUserRequestDto user) {
        companyScopeGuard.requireCompanyScopeForCompanyCode(user.getCompanyCode());
        return companyUserService.createCompanyUser(user, companyScopeGuard.isSuperAdmin());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public CompanyUserResponseDto updateCompanyUser(@PathVariable Long id, @Valid @RequestBody CompanyUserRequestDto user) {
        companyScopeGuard.requireCompanyScopeForStaff(id);
        companyScopeGuard.requireCompanyScopeForCompanyCode(user.getCompanyCode());
        return companyUserService.updateCompanyUser(id, user, companyScopeGuard.isSuperAdmin());
    }

    /**
     * Assigns a company to a staff user. A user cannot assign themselves to a
     * company — company access is granted by a fleet manager through the
     * company access request approval flow.
     */
    @PutMapping("/{id}/company/{companyId}")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<CompanyUserResponseDto> assignCompanyToUser(
            @PathVariable Long id, @PathVariable Long companyId) {
        companyScopeGuard.requireCompanyScopeForStaff(id);
        companyScopeGuard.requireCompanyScope(companyId);
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var currentUserId = (Long) request.getAttribute("nexxauthUserId");
        if (currentUserId != null && currentUserId.equals(id)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(companyUserService.assignCompanyToUser(id, companyId));
    }

    /**
     * Returns the authenticated user's own company membership (company + office).
     * Used by the worker web portal at login: 200 when the user belongs to a
     * company (includes company name + chosen office), 204 when they have not
     * joined a company yet (the portal then shows the join-a-company flow).
     */
    @GetMapping("/me")
    public ResponseEntity<CompanyUserResponseDto> getMe() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        var result = companyUserService.findForSelf(userId);
        return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Self-service office assignment: a worker who has been approved into a
     * company picks their office here. The change is pushed to ikuriyebackend
     * (office-location sync) when the user is a non-driver (worker).
     */
    @PutMapping("/{id}/office/{officeId}")
    public CompanyUserResponseDto assignOffice(
            @PathVariable Long id, @PathVariable Long officeId) {
        companyScopeGuard.requireCompanyScopeForStaff(id);
        return companyUserService.assignOfficeToUser(id, officeId);
    }



    @GetMapping("/{id}")
    public CompanyUserResponseDto getCompanyUser(@PathVariable Long id) {
        companyScopeGuard.requireCompanyScopeForStaff(id);
        return companyUserService.findById(id);
    }

    @GetMapping("/company/{companyId}")
    public List<CompanyUserResponseDto> getByCompanyId(
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit) {
        companyScopeGuard.requireCompanyScope(companyId);
        return companyUserService.findByCompanyId(companyId, timeLimit);
    }

    @GetMapping("/company/{companyId}/drivers")
    public Page<CompanyUserResponseDto> getDriversByCompany(
            @PathVariable Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        companyScopeGuard.requireCompanyScope(companyId);
        return companyUserService.findDriversByCompanyPaged(companyId, timeLimit, query, page, size);
    }

    @GetMapping("/expired-licenses")
    public List<CompanyUserResponseDto> getUsersWithExpiredLicense() {
        Long companyId = companyScopeGuard.requireGlobalOrDefaultCompanyScope();
        return companyUserService.findUsersWithExpiredLicense(companyId);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public void deleteCompanyUser(@PathVariable Long id) {
        companyScopeGuard.requireCompanyScopeForStaff(id);
        companyUserService.deleteCompanyUser(id);
    }
}