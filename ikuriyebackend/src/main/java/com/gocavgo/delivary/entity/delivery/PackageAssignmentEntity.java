package com.gocavgo.delivary.entity.delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "package_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "batch_id")
    private UUID batchId;

    /** Assignment is effective immediately; this records when the driver acceptance became final. */
    @Column(name = "accepted_at")
    private Instant acceptedAt;

    /** Driver may flag the package as not received until this instant. */
    @Column(name = "objection_deadline")
    private Instant objectionDeadline;

    @Column(name = "objected_at")
    private Instant objectedAt;

    @Column(name = "objection_reason", columnDefinition = "TEXT")
    private String objectionReason;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
