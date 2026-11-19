package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.DriverRequestCreateDto;
import com.nexxserve.cavgomain.dto.response.DriverRequestResponseDto;
import com.nexxserve.cavgomain.security.CompanyScopeGuard;
import com.nexxserve.cavgomain.service.DriverRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

/**
 * Driver request management endpoints.
 *
 * <ul>
 *   <li>POST /main/driver-requests — create a request (any authenticated user)</li>
 *   <li>GET /main/driver-requests/my-status — current user's latest request</li>
 *   <li>GET /main/driver-requests/company/{companyId} — list for company (fleet manager)</li>
 *   <li>GET /main/driver-requests/company/{companyId}/pending — pending only (fleet manager)</li>
 *   <li>GET /main/driver-requests/{id} — single request (fleet manager)</li>
 *   <li>POST /main/driver-requests/{id}/approve — approve (fleet manager / supervisor / admin)</li>
 *   <li>POST /main/driver-requests/{id}/reject — reject (fleet manager / supervisor / admin)</li>
 * </ul>
 *
 * <p>Approve/reject record the approving fleet manager on the request (who
 * approved), reject self-approval, and require a staff role — the same rules
 * as company access requests.
 */
@RestController
@RequestMapping("/main/driver-requests")
@RequiredArgsConstructor
public class DriverRequestController {

    private static final Logger log = LoggerFactory.getLogger(DriverRequestController.class);
    private final DriverRequestService driverRequestService;
    private final com.nexxserve.cavgomain.security.NexxauthClient nexxauthClient;
    private final CompanyScopeGuard companyScopeGuard;

    /**
     * Returns the authenticated user's latest driver request status.
     * Used by ikuriye to check if the user already has a pending request
     * before showing the request dialog.
     */
    @GetMapping("/my-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DriverRequestResponseDto> getMyStatus() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        var response = driverRequestService.getMyLatestRequest(userId);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Creates a driver request. The user's identity comes from the JWT.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> createRequest(
            @Valid @RequestBody DriverRequestCreateDto dto) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        var claims = (com.nexxserve.cavgomain.security.NexxauthJwtVerifier.NexxauthClaims)
                request.getAttribute("nexxauthClaims");

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Fetch full profile from Nexxauth to populate the request
        String firstName = "";
        String lastName = null;
        String email = null;
        String phone = null;
        try {
            var nexxauthUser = nexxauthClient.getUser(userId);
            firstName = nexxauthUser.firstName() != null ? nexxauthUser.firstName() : "";
            lastName = nexxauthUser.lastName();
            email = nexxauthUser.email();
            phone = nexxauthUser.phone();
        } catch (Exception e) {
            log.warn("Could not fetch Nexxauth user profile for userId={}: {}", userId, e.getMessage());
        }

        try {
            var response = driverRequestService.createRequest(
                    dto.getCompanyCode(),
                    userId,
                    firstName,
                    lastName,
                    email,
                    phone
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create driver request for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Lists all driver requests for a company (fleet manager view).
     * Returns all requests (all statuses) so the fleet manager can see
     * pending, approved, and rejected requests.
     */
    @GetMapping("/company/{companyId}")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DriverRequestResponseDto>> getCompanyRequests(
            @PathVariable Long companyId) {
        companyScopeGuard.requireCompanyScope(companyId);
        var requests = driverRequestService.getAllRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Lists pending driver requests for a company.
     */
    @GetMapping("/company/{companyId}/pending")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<List<DriverRequestResponseDto>> getPendingCompanyRequests(
            @PathVariable Long companyId) {
        companyScopeGuard.requireCompanyScope(companyId);
        var requests = driverRequestService.getPendingRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Gets a single driver request by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DriverRequestResponseDto> getRequest(@PathVariable Long id) {
        companyScopeGuard.requireCompanyScopeForDriverRequest(id);
        try {
            var response = driverRequestService.getRequestById(id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Approves a driver request — assigns DRIVER role in Nexxauth and records
     * which fleet manager approved. Only staff roles may approve, and a user
     * cannot approve their own request (guarded in the service).
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DriverRequestResponseDto> approveRequest(@PathVariable Long id) {
        companyScopeGuard.requireCompanyScopeForDriverRequest(id);
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        try {
            var response = driverRequestService.approveRequest(id, userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to approve driver request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Rejects a driver request — records who rejected.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN','SUPER_ADMIN')")
    public ResponseEntity<DriverRequestResponseDto> rejectRequest(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        companyScopeGuard.requireCompanyScopeForDriverRequest(id);
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        String reason = body != null ? body.get("reason") : null;
        try {
            var response = driverRequestService.rejectRequest(id, reason, userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to reject driver request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
