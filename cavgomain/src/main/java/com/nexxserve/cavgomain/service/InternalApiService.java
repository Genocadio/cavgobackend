package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.InternalVehicleResponseDto;
import com.nexxserve.cavgomain.dto.response.InternalWorkerResponseDto;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.entity.VehicleLocation;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import com.nexxserve.cavgomain.repository.VehicleLocationRepository;
import com.nexxserve.cavgomain.repository.VehicleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalApiService {

    private final VehicleRepository vehicleRepository;
    private final CompanyUserRepository companyUserRepository;
    private final VehicleLocationRepository vehicleLocationRepository;
    private final VehicleAssignmentRepository vehicleAssignmentRepository;

    public List<InternalVehicleResponseDto> getAllVehicles() {
        return vehicleRepository.findAllWithActiveAssignments().stream()
                .map(this::toInternalVehicleDto)
                .collect(Collectors.toList());
    }

    public InternalVehicleResponseDto getVehicleById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Vehicle id cannot be null");
        }
        Vehicle vehicle = vehicleRepository.findByIdWithActiveAssignment(id)
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found with id: " + id));
        return toInternalVehicleDto(vehicle);
    }

    public List<InternalVehicleResponseDto> getVehiclesByCompany(Long companyId) {
        return vehicleRepository.findByCompanyIdWithActiveAssignments(companyId).stream()
                .map(this::toInternalVehicleDto)
                .collect(Collectors.toList());
    }

    public List<InternalWorkerResponseDto> getAllWorkers() {
        // Return all company users (not just drivers) - they can be ADMIN, DRIVER, FLEET_MANAGER, SUPERVISOR
        return companyUserRepository.findAll().stream()
                .map(this::toInternalWorkerDto)
                .collect(Collectors.toList());
    }

    public InternalWorkerResponseDto getWorkerById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Worker id cannot be null");
        }
        CompanyUser worker = companyUserRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Worker not found with id: " + id));
        
        return toInternalWorkerDto(worker);
    }

    public List<InternalWorkerResponseDto> getWorkersByCompany(Long companyId) {
        // Return all company users for the company (not just drivers)
        return companyUserRepository.findByCompanyId(companyId).stream()
                .map(this::toInternalWorkerDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public InternalWorkerResponseDto toggleWorkerStatus(Long id) {
        CompanyUser worker = companyUserRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Worker not found with id: " + id));

        // Toggle status: ACTIVE -> INACTIVE, INACTIVE -> ACTIVE, others -> ACTIVE
        if (worker.getStatus() == com.nexxserve.cavgomain.enums.UserStatus.ACTIVE) {
            worker.setStatus(com.nexxserve.cavgomain.enums.UserStatus.INACTIVE);
        } else {
            worker.setStatus(com.nexxserve.cavgomain.enums.UserStatus.ACTIVE);
        }
        
        CompanyUser saved = companyUserRepository.save(worker);
        return toInternalWorkerDto(saved);
    }

    /**
     * Get company user by ID (for getting companyId)
     */
    public CompanyUser getCompanyUserById(Long id) {
        return companyUserRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Company user not found with id: " + id));
    }

    private InternalVehicleResponseDto toInternalVehicleDto(Vehicle vehicle) {
        // Get latest location
        Optional<VehicleLocation> latestLocationOpt = vehicleLocationRepository
                .findTopByVehicleIdOrderByRecordedAtDesc(vehicle.getId());
        
        InternalVehicleResponseDto.InternalVehicleLocationDto locationDto = null;
        if (latestLocationOpt.isPresent()) {
            VehicleLocation location = latestLocationOpt.get();
            locationDto = new InternalVehicleResponseDto.InternalVehicleLocationDto();
            locationDto.setLatitude(location.getLatitude());
            locationDto.setLongitude(location.getLongitude());
            locationDto.setAddress(null); // As specified, address can be null
            locationDto.setBearing(location.getBearing());
            locationDto.setSpeed(location.getSpeed());
            
            // Convert timestamp to ISO 8601 format
            if (location.getTimestamp() != null) {
                Instant instant = Instant.ofEpochMilli(location.getTimestamp());
                locationDto.setTimestamp(instant.toString());
            } else if (location.getRecordedAt() != null) {
                locationDto.setTimestamp(location.getRecordedAt().toInstant(ZoneOffset.UTC).toString());
            }
        }
        
        return InternalVehicleResponseDto.fromEntity(vehicle, locationDto);
    }

    private InternalWorkerResponseDto toInternalWorkerDto(CompanyUser worker) {
        // Get assigned vehicle if exists (only for DRIVER role)
        InternalVehicleResponseDto vehicleDto = null;
        if (worker.getRole() == CompanyUserRole.DRIVER) {
            List<VehicleAssignment> activeAssignments = vehicleAssignmentRepository
                    .findActiveAssignmentsByDriver(worker.getId());
            
            if (!activeAssignments.isEmpty()) {
                VehicleAssignment assignment = activeAssignments.get(0);
                Vehicle vehicle = assignment.getVehicle();
                vehicleDto = toInternalVehicleDto(vehicle);
            }
        }
        
        return InternalWorkerResponseDto.fromEntity(worker, vehicleDto);
    }
}

