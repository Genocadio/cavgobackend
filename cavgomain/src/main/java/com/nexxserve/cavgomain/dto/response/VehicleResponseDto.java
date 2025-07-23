package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.AssignmentStatus;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.VehicleType;
import lombok.Data;

import java.util.Optional;

@Data
public class VehicleResponseDto {
    private Long id;
    private Long companyId;
    private String companyName;
    private String make;
    private String model;
    private int capacity;
    private String licensePlate;
    private VehicleType vehicleType;
    private VehicleStatus status;
    private String createdAt;
    private String updatedAt;
    private CompanyUserResponseDto driver;

    public static VehicleResponseDto fromEntity(Vehicle entity) {
        VehicleResponseDto dto = new VehicleResponseDto();
        dto.setId(entity.getId());
        dto.setCompanyId(entity.getCompany().getId());
        dto.setCompanyName(entity.getCompany().getCompanyName());
        dto.setMake(entity.getMake());
        dto.setModel(entity.getModel());
        dto.setCapacity(entity.getCapacity());
        dto.setLicensePlate(entity.getLicensePlate());
        dto.setVehicleType(entity.getVehicleType());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);
        Optional<VehicleAssignment> activeAssignment = entity.getAssignments().stream()
                .filter(assignment -> assignment.getStatus() == AssignmentStatus.ACTIVE)
                .findFirst();
        if (activeAssignment.isPresent()) {
            VehicleAssignment assignment = activeAssignment.get();
            dto.setDriver(CompanyUserResponseDto.fromEntity(assignment.getDriver()));
        } else {
            dto.setDriver(null);
        }
        return dto;
    }
}