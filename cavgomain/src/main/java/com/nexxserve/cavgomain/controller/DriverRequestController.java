package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.DriverRequestCreateDto;
import com.nexxserve.cavgomain.dto.response.DriverRequestResponseDto;
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
 *   <li>GET /main/driver-requests — list own requests</li>
 *   <li>GET /main/driver-requests/company/{companyId} — list pending for company (fleet manager)</li>
 *   <li>POST /main/driver-requests/{id}/approve — approve (fleet manager)</li>
 *   <li>POST /main/driver-requests/{id}/reject — reject (fleet manager)</li>
 * </ul>
 */
@RestController
@RequestMapping("/main/driver-requests")
@RequiredArgsConstructor
public class DriverRequestController {

    private static final Logger log = LoggerFactory.getLogger(DriverRequestController.class);
    private final DriverRequestService driverRequestService;
    private final com.nexxserve.cavgomain.security.NexxauthClient nexxauthClient;

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
    public ResponseEntity<DriverRequestResponseDto> createRequest(
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
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Lists all driver requests for a company (fleet manager view).
     * Returns all requests (all statuses) so the fleet manager can see
     * pending, approved, and rejected requests.
     */
    @GetMapping("/company/{companyId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DriverRequestResponseDto>> getCompanyRequests(
            @PathVariable Long companyId) {
        var requests = driverRequestService.getAllRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Lists pending driver requests for a company.
     */
    @GetMapping("/company/{companyId}/pending")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<DriverRequestResponseDto>> getPendingCompanyRequests(
            @PathVariable Long companyId) {
        var requests = driverRequestService.getPendingRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Gets a single driver request by ID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DriverRequestResponseDto> getRequest(@PathVariable Long id) {
        try {
            var response = driverRequestService.getRequestById(id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Approves a driver request — assigns DRIVER role in Nexxauth.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DriverRequestResponseDto> approveRequest(@PathVariable Long id) {
        try {
            var response = driverRequestService.approveRequest(id);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to approve driver request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Rejects a driver request.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DriverRequestResponseDto> rejectRequest(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        try {
            var response = driverRequestService.rejectRequest(id, reason);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to reject driver request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
