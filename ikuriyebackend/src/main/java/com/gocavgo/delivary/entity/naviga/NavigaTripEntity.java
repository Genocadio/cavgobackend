package com.gocavgo.delivary.entity.naviga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "naviga_trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NavigaTripEntity {

    @Id
    @Column(name = "naviga_trip_id")
    private Long navigaTripId;

    @Column(name = "car_id", nullable = false)
    private String carId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "naviga_created_at")
    private Instant navigaCreatedAt;

    @Column(name = "naviga_completed_at")
    private Instant navigaCompletedAt;

    /** Full waypoint progress JSON from Naviga (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "waypoint_progresses", columnDefinition = "jsonb")
    private String waypointProgressesJson;

    /** Current location JSON from Naviga (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_location", columnDefinition = "jsonb")
    private String currentLocationJson;

    /** When this event was published by cavgomqt. */
    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    /** Event source: naviga-trip-create, naviga-gps-batch, naviga-trip-delete. */
    @Column(name = "source")
    private String source;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When to auto-delete this trip record (completion_time + 10 hours). */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.receivedAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
