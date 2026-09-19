package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.response.*;
import com.nexxserve.cavgomain.service.AggregatorSyncService;
import com.nexxserve.cavgomain.service.InternalApiService;
import com.nexxserve.cavgomain.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/api")
@RequiredArgsConstructor
public class InternalApiController {

    private static final Logger log = LoggerFactory.getLogger(InternalApiController.class);
    private final InternalApiService internalApiService;
    private final AggregatorSyncService aggregatorSyncService;
    private final UserService userService;

    // ── Company endpoints (for adminaggregate, no auth required) ──

    @GetMapping("/companies")
    public ResponseEntity<List<CompanyResponseDto>> getAllCompanies() {
        return ResponseEntity.ok(internalApiService.getAllCompanies());
    }

    @GetMapping("/companies/{companyId}/vehicles")
    public ResponseEntity<List<VehicleResponseDto>> getCompanyVehicles(@PathVariable Long companyId) {
        return ResponseEntity.ok(internalApiService.getVehiclesByCompanyDto(companyId));
    }

    @GetMapping("/companies/{companyId}/drivers")
    public ResponseEntity<List<CompanyUserResponseDto>> getCompanyDrivers(@PathVariable Long companyId) {
        return ResponseEntity.ok(internalApiService.getDriversByCompanyDto(companyId));
    }

    // Vehicle endpoints
    @GetMapping("/vehicles")
    public ResponseEntity<List<InternalVehicleResponseDto>> getAllVehicles() {
        return ResponseEntity.ok(internalApiService.getAllVehicles());
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<VehicleResponseDto> getVehicleById(@PathVariable Long id) {
        return ResponseEntity.ok(internalApiService.getVehicleById(id));
    }

    @GetMapping("/vehicles/company/{companyId}")
    public ResponseEntity<List<InternalVehicleResponseDto>> getVehiclesByCompany(@PathVariable Long companyId) {
        List<InternalVehicleResponseDto> vehicles = internalApiService.getVehiclesByCompany(companyId);
        // Schedule sync for this company (will cancel existing timer if any)
        aggregatorSyncService.scheduleCompanySync(companyId);
        return ResponseEntity.ok(vehicles);
    }

    /**
     * Sets a vehicle's operational status. Called by cavgotrips to flag a vehicle
     * OCCUPIED while it has an active trip and AVAILABLE once a trip is completed,
     * cancelled or deleted. No auth — internal only.
     */
    @PutMapping("/vehicles/{id}/status")
    public ResponseEntity<VehicleResponseDto> setVehicleStatus(@PathVariable Long id,
                                                               @RequestParam("status") String status) {
        return ResponseEntity.ok(internalApiService.setVehicleStatus(id, status));
    }

    // Worker endpoints
    @GetMapping("/workers")
    public ResponseEntity<List<InternalWorkerResponseDto>> getAllWorkers() {
        return ResponseEntity.ok(internalApiService.getAllWorkers());
    }

    @GetMapping("/workers/{id}")
    public ResponseEntity<InternalWorkerResponseDto> getWorkerById(@PathVariable Long id) {
        InternalWorkerResponseDto worker = internalApiService.getWorkerById(id);
        return ResponseEntity.ok(worker);
    }

    @GetMapping("/workers/company/{companyId}")
    public ResponseEntity<List<InternalWorkerResponseDto>> getWorkersByCompany(@PathVariable Long companyId) {
        List<InternalWorkerResponseDto> workers = internalApiService.getWorkersByCompany(companyId);
        // Schedule sync for this company (will cancel existing timer if any)
        aggregatorSyncService.scheduleCompanySync(companyId);
        return ResponseEntity.ok(workers);
    }

    // Toggle worker status
    @PutMapping("/workers/{id}/status")
    public ResponseEntity<InternalWorkerResponseDto> toggleWorkerStatus(@PathVariable Long id) {
        InternalWorkerResponseDto worker = internalApiService.toggleWorkerStatus(id);
        
        // Get company ID from worker and trigger immediate sync
        try {
            com.nexxserve.cavgomain.entity.CompanyUser companyUser = 
                internalApiService.getCompanyUserById(id);
            if (companyUser != null && companyUser.getCompany() != null) {
                aggregatorSyncService.syncCompanyDataImmediately(companyUser.getCompany().getId());
            }
        } catch (Exception e) {
            // Log error but don't fail the request
            org.slf4j.LoggerFactory.getLogger(InternalApiController.class)
                .error("Error triggering immediate sync after status toggle", e);
        }
        
        return ResponseEntity.ok(worker);
    }

    // ── User sync (called by ikuriyebackend) ─────────────────────────────────

    /**
     * Syncs a user from ikuriyebackend into cavgomain's local DB only when the
     * user already exists here. Response contract (error-guarded, no Nexxauth
     * wait):
     * <ul>
     *   <li>404 — user unknown to cavgomain. ikuriyebackend does NOT wait for a
     *       Nexxauth call; it falls back to its own provisioning.</li>
     *   <li>200 — the current user (with its assigned office, incl. the office
     *       location id) is returned immediately, even when the user "needs an
     *       update" — no Nexxauth update is awaited.</li>
     * </ul>
     */
    @PostMapping("/users/sync")
    public ResponseEntity<CompanyUserResponseDto> syncUserFromIkuriye(@RequestBody SyncUserRequest request) {
        if (request.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var response = userService.findForIkuriyeSync(request.userId(), null);
            if (response.isEmpty()) {
                log.info("Internal sync: user not found in cavgomain, userId={}", request.userId());
                return ResponseEntity.notFound().build();
            }
            log.info("Internal sync completed for userId={}", response.get().getId());
            return ResponseEntity.ok(response.get());
        } catch (Exception e) {
            log.error("Internal sync failed for userId={}: {}", request.userId(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Request body for internal user sync. */
    public record SyncUserRequest(Long userId) {}
}

