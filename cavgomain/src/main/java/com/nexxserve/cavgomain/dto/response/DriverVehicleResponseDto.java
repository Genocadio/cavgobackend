package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.AssignmentStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Data
public class DriverVehicleResponseDto {
    // Driver information
    private Long id;
    private Long companyId;
    private String companyName;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserStatus status;
    private LocalDate dateOfBirth;
    private String address;
    private CompanyUserRole role;
    private String licenseNumber;
    private LocalDate licenseExpiry;

    // Vehicle information (if assigned)
    private Boolean hasAssignedVehicle;
    private Long vehicleId;
    private String vehicleMake;
    private String vehicleModel;
    private String licensePlate;
    private LocalDateTime assignmentDate;

    private String createdAt;
    private String updatedAt;

    public static DriverVehicleResponseDto fromEntity(CompanyUser driver) {
        DriverVehicleResponseDto dto = new DriverVehicleResponseDto();
        dto.setId(driver.getId());
        dto.setCompanyId(driver.getCompany().getId());
        dto.setCompanyName(driver.getCompany().getCompanyName());
        dto.setFirstName(driver.getFirstName());
        dto.setLastName(driver.getLastName());
        dto.setEmail(driver.getEmail());
        dto.setPhone(driver.getPhone());
        dto.setStatus(driver.getStatus());
        dto.setDateOfBirth(driver.getDateOfBirth());
        dto.setAddress(driver.getAddress());
        dto.setRole(driver.getRole());
        dto.setLicenseNumber(driver.getLicenseNumber());
        dto.setLicenseExpiry(driver.getLicenseExpiry());
        dto.setCreatedAt(driver.getCreatedAt().toString());
        dto.setUpdatedAt(driver.getUpdatedAt() != null ? driver.getUpdatedAt().toString() : null);

        // Get active vehicle assignment
        Optional<VehicleAssignment> activeAssignment = driver.getVehicleAssignments().stream()
                .filter(assignment -> assignment.getStatus() == AssignmentStatus.ACTIVE)
                .findFirst();

        if (activeAssignment.isPresent()) {
            VehicleAssignment assignment = activeAssignment.get();
            dto.setHasAssignedVehicle(true);
            dto.setVehicleId(assignment.getVehicle().getId());
            dto.setVehicleMake(assignment.getVehicle().getMake());
            dto.setVehicleModel(assignment.getVehicle().getModel());
            dto.setLicensePlate(assignment.getVehicle().getLicensePlate());
            dto.setAssignmentDate(assignment.getAssignedDate());
        } else {
            dto.setHasAssignedVehicle(false);
        }

        return dto;
    }
}