package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.CompanyAccessRequestCreateDto;
import com.nexxserve.cavgomain.dto.response.CompanyAccessRequestResponseDto;
import com.nexxserve.cavgomain.service.CompanyAccessRequestService;
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
 * Company access request endpoints.
 *
 * <ul>
 *   <li>POST /main/company-access-requests — request access (any authenticated user)</li>
 *   <li>GET /main/company-access-requests/my-status — current user's latest request</li>
 *   <li>GET /main/company-access-requests/company/{companyId} — list for company (fleet manager)</li>
 *   <li>GET /main/company-access-requests/company/{companyId}/pending — pending only</li>
 *   <li>POST /main/company-access-requests/{id}/approve — approve (fleet manager / supervisor / admin)</li>
 *   <li>POST /main/company-access-requests/{id}/reject — reject (fleet manager / supervisor / admin)</li>
 * </ul>
 *
 * <p>Self-approval is rejected in the service — a user can request access to
 * a company but must be approved by another staff member of that company.
 */
@RestController
@RequestMapping("/main/company-access-requests")
@RequiredArgsConstructor
public class CompanyAccessRequestController {

    private static final Logger log = LoggerFactory.getLogger(CompanyAccessRequestController.class);

    private final CompanyAccessRequestService companyAccessRequestService;
    private final com.nexxserve.cavgomain.security.NexxauthClient nexxauthClient;

    /**
     * Returns the authenticated user's latest company access request status.
     * Used by the app to show the current status while awaiting approval.
     */
    @GetMapping("/my-status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CompanyAccessRequestResponseDto> getMyStatus() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        var response = companyAccessRequestService.getMyLatestRequest(userId);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Creates a company access / fleet-manager role request. The user's
     * identity comes from the JWT; the requested role is FLEET_MANAGER.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> createRequest(
            @Valid @RequestBody CompanyAccessRequestCreateDto dto) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
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
            var response = companyAccessRequestService.createRequest(
                    dto.getCompanyCode(),
                    userId,
                    firstName,
                    lastName,
                    email,
                    phone
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create company access request for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Lists all company access requests for a company (fleet manager view).
     */
    @GetMapping("/company/{companyId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CompanyAccessRequestResponseDto>> getCompanyRequests(
            @PathVariable Long companyId) {
        var requests = companyAccessRequestService.getAllRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Lists pending company access requests for a company.
     */
    @GetMapping("/company/{companyId}/pending")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<CompanyAccessRequestResponseDto>> getPendingCompanyRequests(
            @PathVariable Long companyId) {
        var requests = companyAccessRequestService.getPendingRequestsByCompany(companyId);
        return ResponseEntity.ok(requests);
    }

    /**
     * Approves a company access request — grants the FLEET_MANAGER role in
     * Nexxauth and assigns the user to the company, recording who approved.
     * Only fleet manager, supervisor, or admin roles may approve, and a user
     * cannot approve their own request.
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN')")
    public ResponseEntity<CompanyAccessRequestResponseDto> approveRequest(@PathVariable Long id) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        try {
            var response = companyAccessRequestService.approveRequest(id, userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to approve company access request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Rejects a company access request.
     */
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('FLEET_MANAGER','SUPERVISOR','ADMIN')")
    public ResponseEntity<CompanyAccessRequestResponseDto> rejectRequest(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        String reason = body != null ? body.get("reason") : null;
        try {
            var response = companyAccessRequestService.rejectRequest(id, reason, userId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to reject company access request {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}