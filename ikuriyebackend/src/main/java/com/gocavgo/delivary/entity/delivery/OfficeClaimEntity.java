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

/**
 * A destination office worker taking custody directly from a driver, without
 * waiting for the driver to create a transfer. The driver can contest the
 * handoff as "not delivered" during a bounded window.
 */
@Entity
@Table(name = "office_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfficeClaimEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "claimed_by", nullable = false)
    private Long claimedBy;

    @Column(name = "office_location_id", nullable = false)
    private UUID officeLocationId;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt;

    @Column(name = "dispute_deadline", nullable = false)
    private Instant disputeDeadline;

    @Column(name = "disputed_at")
    private Instant disputedAt;

    @Column(name = "dispute_reason", columnDefinition = "TEXT")
    private String disputeReason;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
