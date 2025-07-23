package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.AssignmentStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "vehicle_assignments")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class VehicleAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    @ToString.Exclude
    private Vehicle vehicle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", nullable = false)
    @ToString.Exclude
    private CompanyUser driver;

    @Column(name = "assigned_date", nullable = false)
    private java.time.LocalDateTime assignedDate;

    @Column(name = "unassigned_date")
    private java.time.LocalDateTime unassignedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private AssignmentStatus status = AssignmentStatus.ACTIVE;

    @Column(name = "notes")
    private String notes;
}