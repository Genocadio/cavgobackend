package com.nexxserve.cavgomqt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Per-device ACK cursor. Two phones for one driver are tracked independently. */
@Entity
@Table(name = "driver_state_acks", indexes = {
        @Index(name = "idx_driver_state_ack_driver", columnList = "driver_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DriverStateAckEntity {

    /** driverId + ":" + deviceId */
    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "last_acked_revision")
    private Long lastAckedRevision = 0L;

    @Column(name = "last_reported_revision")
    private Long lastReportedRevision = 0L;

    @Column(name = "last_report_vehicle_id")
    private Long lastReportVehicleId;

    @Column(name = "last_report_trip_id")
    private Long lastReportTripId;

    @Column(name = "updated_at")
    private Long updatedAt;
}
