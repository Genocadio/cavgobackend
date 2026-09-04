package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.InternalVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.InternalWorkerResponseDto;
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

    // Vehicle endpoints
    @GetMapping("/vehicles")
    public ResponseEntity<List<InternalVehicleResponseDto>> getAllVehicles() {
        return ResponseEntity.ok(internalApiService.getAllVehicles());
    }

    @GetMapping("/vehicles/{id}")
    public ResponseEntity<InternalVehicleResponseDto> getVehicleById(@PathVariable Long id) {
        return ResponseEntity.ok(internalApiService.getVehicleById(id));
    }

    @GetMapping("/vehicles/company/{companyId}")
    public ResponseEntity<List<InternalVehicleResponseDto>> getVehiclesByCompany(@PathVariable Long companyId) {
        List<InternalVehicleResponseDto> vehicles = internalApiService.getVehiclesByCompany(companyId);
        // Schedule sync for this company (will cancel existing timer if any)
        aggregatorSyncService.scheduleCompanySync(companyId);
        return ResponseEntity.ok(vehicles);
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
     * Syncs a user from Nexxauth into cavgomain's local DB. Called by ikuriyebackend
     * when a WORKER or DRIVER token is verified — ensures cavgomain always has an
     * up-to-date mirror of workers/drivers that ikuriye serves.
     *
     * <p>This is a fire-and-forget internal endpoint — no auth required.
     * The caller should not block on the response.
     */
    @PostMapping("/users/sync")
    public ResponseEntity<CompanyUserResponseDto> syncUserFromIkuriye(@RequestBody SyncUserRequest request) {
        log.info("Internal sync requested for userId={}", request.userId());
        if (request.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var response = userService.syncUser(request.userId());
            log.info("Internal sync completed for userId={}", response.getId());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Internal sync failed for userId={}: {}", request.userId(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Request body for internal user sync. */
    public record SyncUserRequest(Long userId) {}
}

