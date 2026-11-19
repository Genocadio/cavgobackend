package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyAdminRoleUpdateDto;
import com.nexxserve.cavgomain.dto.response.CompanyAccessRequestResponseDto;
import com.nexxserve.cavgomain.dto.response.CompanyAdminAccessRequestResponseDto;
import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.security.CompanyScopeGuard;
import com.nexxserve.cavgomain.service.CompanyAccessRequestService;
import com.nexxserve.cavgomain.service.CompanyAdminAccessRequestService;
import com.nexxserve.cavgomain.service.CompanyAdminManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/main/company-admin/companies/{companyId}")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class CompanyAdminManagementController {

    private final CompanyScopeGuard companyScopeGuard;
    private final CompanyAdminManagementService managementService;
    private final CompanyAccessRequestService companyAccessRequestService;
    private final CompanyAdminAccessRequestService adminAccessRequestService;

    @GetMapping("/staff")
    public List<CompanyUserResponseDto> listStaff(@PathVariable Long companyId) {
        companyScopeGuard.requireCompanyAdminScope(companyId);
        return managementService.listStaff(companyId);
    }

    @PutMapping("/staff/{staffId}/role")
    public ResponseEntity<?> updateRole(
            @PathVariable Long companyId,
            @PathVariable Long staffId,
            @Valid @RequestBody CompanyAdminRoleUpdateDto body) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        try {
            return ResponseEntity.ok(managementService.updateRole(
                    companyId, callerId, staffId, body.getRole(), companyScopeGuard.isSuperAdmin()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/staff/{staffId}")
    public ResponseEntity<?> removeFromCompany(
            @PathVariable Long companyId, @PathVariable Long staffId) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        try {
            managementService.removeFromCompany(companyId, callerId, staffId, companyScopeGuard.isSuperAdmin());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/staff/{staffId}/driver-assignments")
    public ResponseEntity<?> unassignDriver(
            @PathVariable Long companyId, @PathVariable Long staffId) {
        companyScopeGuard.requireCompanyAdminScope(companyId);
        try {
            managementService.unassignDriver(companyId, staffId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/access-requests")
    public List<CompanyAccessRequestResponseDto> listAccessRequests(@PathVariable Long companyId) {
        companyScopeGuard.requireCompanyAdminScope(companyId);
        return companyAccessRequestService.getPendingRequestsByCompany(companyId);
    }

    @PostMapping("/access-requests/{requestId}/approve")
    public ResponseEntity<?> approveAccessRequest(
            @PathVariable Long companyId, @PathVariable Long requestId) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        companyScopeGuard.requireCompanyAdminScopeForCompanyAccessRequest(companyId, requestId);
        try {
            return ResponseEntity.ok(companyAccessRequestService.approveRequest(requestId, callerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/access-requests/{requestId}/reject")
    public ResponseEntity<?> rejectAccessRequest(
            @PathVariable Long companyId,
            @PathVariable Long requestId,
            @RequestBody(required = false) Map<String, String> body) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        companyScopeGuard.requireCompanyAdminScopeForCompanyAccessRequest(companyId, requestId);
        try {
            String reason = body == null ? null : body.get("reason");
            return ResponseEntity.ok(companyAccessRequestService.rejectRequest(requestId, reason, callerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/admin-access-requests")
    public List<CompanyAdminAccessRequestResponseDto> listAdminAccessRequests(@PathVariable Long companyId) {
        companyScopeGuard.requireCompanyAdminScope(companyId);
        return adminAccessRequestService.getPendingRequests(companyId);
    }

    @PostMapping("/admin-access-requests/{requestId}/approve")
    public ResponseEntity<?> approveAdminAccessRequest(
            @PathVariable Long companyId, @PathVariable Long requestId) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        companyScopeGuard.requireCompanyAdminScopeForAdminAccessRequest(companyId, requestId);
        try {
            return ResponseEntity.ok(adminAccessRequestService.approve(companyId, requestId, callerId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/admin-access-requests/{requestId}/reject")
    public ResponseEntity<?> rejectAdminAccessRequest(
            @PathVariable Long companyId,
            @PathVariable Long requestId,
            @RequestBody(required = false) Map<String, String> body) {
        Long callerId = companyScopeGuard.requireCompanyAdminScope(companyId);
        companyScopeGuard.requireCompanyAdminScopeForAdminAccessRequest(companyId, requestId);
        try {
            String reason = body == null ? null : body.get("reason");
            return ResponseEntity.ok(adminAccessRequestService.reject(companyId, requestId, callerId, reason));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }
}
