package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.response.InternalVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.InternalWorkerResponseDto;
import com.nexxserve.cavgomain.service.AggregatorSyncService;
import com.nexxserve.cavgomain.service.InternalApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/api")
@RequiredArgsConstructor
public class InternalApiController {

    private final InternalApiService internalApiService;
    private final AggregatorSyncService aggregatorSyncService;

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
}

