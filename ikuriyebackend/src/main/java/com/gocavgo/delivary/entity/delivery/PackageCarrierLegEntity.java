package com.gocavgo.delivary.entity.delivery;

import com.gocavgo.delivary.enums.delivery.CustodianRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of the trip/company context captured when a driver takes
 * custody of a package (by accepting a transfer or by being assigned).
 *
 * <p>The trip mirror in {@code cavgo_trips} is short lived (completed trips
 * expire after an hour, cancelled trips are deleted immediately), so vehicle
 * and company references are copied here to keep reporting historical.
 */
@Entity
@Table(name = "package_carrier_legs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageCarrierLegEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    /** The driver's active trip at the moment of custody. Null for private packages. */
    @Column(name = "trip_id")
    private Long tripId;

    /** Driver's company in the cavgomain office-UUID space. */
    @Column(name = "company_id")
    private UUID companyId;

    /** Company id from the trip service, for admin joins. */
    @Column(name = "company_ref_id")
    private Long companyRefId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    /** JSON snapshot of the vehicle from the trip service. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_json", columnDefinition = "jsonb")
    private String vehicleJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "custodian_role", nullable = false)
    private CustodianRole custodianRole;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
