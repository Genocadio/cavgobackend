package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.AssignmentStatus;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VehicleAssignmentResponseDto {
    private Long id;

    private Long vehicleId;
    private String vehicleMake;
    private String vehicleModel;
    private String licensePlate;

    private Long driverId;
    private String driverName;

    private LocalDateTime assignedDate;
    private LocalDateTime unassignedDate;
    private AssignmentStatus status;
    private String notes;

    private String createdAt;
    private String updatedAt;

    public static VehicleAssignmentResponseDto fromEntity(VehicleAssignment entity) {
        VehicleAssignmentResponseDto dto = new VehicleAssignmentResponseDto();
        dto.setId(entity.getId());

        // Vehicle information
        dto.setVehicleId(entity.getVehicle().getId());
        dto.setVehicleMake(entity.getVehicle().getMake());
        dto.setVehicleModel(entity.getVehicle().getModel());
        dto.setLicensePlate(entity.getVehicle().getLicensePlate());

        // Driver information
        dto.setDriverId(entity.getDriver().getId());
        dto.setDriverName(entity.getDriver().getFirstName() + " " + entity.getDriver().getLastName());

        dto.setAssignedDate(entity.getAssignedDate());
        dto.setUnassignedDate(entity.getUnassignedDate());
        dto.setStatus(entity.getStatus());
        dto.setNotes(entity.getNotes());

        dto.setCreatedAt(entity.getCreatedAt().toString());
        dto.setUpdatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null);

        return dto;
    }
}