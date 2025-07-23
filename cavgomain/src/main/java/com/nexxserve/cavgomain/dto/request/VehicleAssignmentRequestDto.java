package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.AssignmentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VehicleAssignmentRequestDto {

    @NotNull(message = "Vehicle ID is required")
    private Long vehicleId;

    @NotNull(message = "Driver ID is required")
    private Long driverId;

    @NotNull(message = "Assigned date is required")
    private LocalDateTime assignedDate;

    private LocalDateTime unassignedDate;

    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    private String notes;

    public VehicleAssignment toEntity(Vehicle vehicle, CompanyUser driver) {
        VehicleAssignment assignment = new VehicleAssignment();
        assignment.setVehicle(vehicle);
        assignment.setDriver(driver);
        assignment.setAssignedDate(this.assignedDate);
        assignment.setUnassignedDate(this.unassignedDate);
        assignment.setStatus(this.status);
        assignment.setNotes(this.notes);
        return assignment;
    }
}