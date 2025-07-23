package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.VehicleRequestDto;
import com.nexxserve.cavgomain.dto.response.VehicleAssignmentResponseDto;
import com.nexxserve.cavgomain.dto.response.VehicleResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.VehicleRepository;
import com.nexxserve.cavgomain.repository.VehicleAssignmentRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.geom.PathIterator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final VehicleAssignmentRepository assignmentRepository;
    private final CompanyUserRepository companyUserRepository;
    private final CompanyRepository companyRepository;


    // CRUD methods
    public VehicleResponseDto createVehicle(VehicleRequestDto vehicle) {
        if (vehicleRepository.existsByLicensePlate(vehicle.getLicensePlate())) {
            throw new IllegalArgumentException("Vehicle with this license plate already exists");
        }
        Company company = companyRepository.findById(vehicle.getCompanyId()).orElseThrow(EntityNotFoundException::new);
        Vehicle newVehicle = vehicle.toEntity(company);

        return VehicleResponseDto.fromEntity(vehicleRepository.save(newVehicle));
    }

    public Vehicle getVehicle(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vehicle not found"));
    }

    public VehicleResponseDto getVehicleResponse(Long id) {
        return VehicleResponseDto.fromEntity(getVehicle(id));
    }

    public List<VehicleResponseDto> getAllVehicles() {
        return vehicleRepository.findAll().stream()
                .map(VehicleResponseDto::fromEntity)
                .toList();
    }

    public VehicleResponseDto updateVehicle(Long id, VehicleRequestDto updated) {
        Vehicle vehicle = getVehicle(id);
        vehicle.setMake(updated.getMake());
        vehicle.setModel(updated.getModel());
        vehicle.setLicensePlate(updated.getLicensePlate());
        vehicle.setVehicleType(updated.getVehicleType());
        vehicle.setStatus(updated.getStatus());
        return VehicleResponseDto.fromEntity(vehicleRepository.save(vehicle));
    }

    public void deleteVehicle(Long id) {
        vehicleRepository.deleteById(id);
    }

    // Assign vehicle to driver
   @Transactional
   public VehicleAssignmentResponseDto assignVehicleToDriver(Long vehicleId, Long driverId, String notes) {
       Vehicle vehicle = getVehicle(vehicleId);
       if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
           throw new IllegalStateException("Vehicle is not available for assignment");
       }

       CompanyUser driver = companyUserRepository.findById(driverId)
               .orElseThrow(() -> new EntityNotFoundException("Driver not found"));

       if (driver.getRole() != CompanyUserRole.DRIVER) {
           throw new IllegalArgumentException("User is not a driver");
       }

       // End any previous active assignment for this vehicle
       Optional<VehicleAssignment> activeAssignmentForVehicle =
               assignmentRepository.findActiveAssignmentByVehicle(vehicleId);
       activeAssignmentForVehicle.ifPresent(assignment -> {
           assignment.setUnassignedDate(LocalDateTime.now());
           // If you have AssignmentStatus, set assignment.setStatus(AssignmentStatus.INACTIVE);
           assignmentRepository.save(assignment);
       });

       // Check if driver already has an active assignment
       List<VehicleAssignment> activeAssignmentsForDriver =
               assignmentRepository.findActiveAssignmentsByDriver(driverId);
       if (!activeAssignmentsForDriver.isEmpty()) {
           throw new IllegalStateException("Driver already has an assigned vehicle");
       }

       // Assign vehicle
       VehicleAssignment assignment = new VehicleAssignment();
       assignment.setVehicle(vehicle);
       assignment.setDriver(driver);
       assignment.setAssignedDate(LocalDateTime.now());
       assignment.setNotes(notes);

       vehicle.setStatus(VehicleStatus.OCCUPIED);
       vehicleRepository.save(vehicle);

       return VehicleAssignmentResponseDto.fromEntity(assignmentRepository.save(assignment));
   }
}