package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.entity.CompanyUser;
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
    private String initialPassword; // only set on creation
    private VehicleLocationResponseDto lastLocation;
    private Boolean isOnline;
    private String lastOnlineAt;

    public static VehicleResponseDto fromEntity(Vehicle entity, CompanyUser driver) {
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
        
        // Only populate driver if provided (prevents recursion)
        if (driver != null) {
            dto.setDriver(CompanyUserResponseDto.fromEntity(driver));
        } else {
            dto.setDriver(null);
        }
        
        // Populate online status and last online timestamp
        dto.setIsOnline(entity.isOnline());
        dto.setLastOnlineAt(entity.getLastOnlineAt() != null ? entity.getLastOnlineAt().toString() : null);
        
        // Note: lastLocation is not populated here to avoid performance issues
        // Use the dedicated endpoint GET /vehicles/{id}/location/latest to fetch the latest location
        
        return dto;
    }

    // Keep existing method for backward compatibility, auto-populate driver
    public static VehicleResponseDto fromEntity(Vehicle entity) {
        Optional<VehicleAssignment> activeAssignment = entity.getAssignments().stream()
                .filter(assignment -> assignment.getStatus() == AssignmentStatus.ACTIVE)
                .findFirst();
        
        CompanyUser driver = activeAssignment.map(VehicleAssignment::getDriver).orElse(null);
        return fromEntity(entity, driver);
    }
}