package com.gocavgo.delivary.entity.trip;

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

/**
 * Mirror of trips published by the cavgotrips trip service on the
 * {@code tripservice.trips.updates} fanout exchange. Keyed by the trip service
 * id and denormalised with the driver/vehicle snapshot so trips can be
 * queried per driver.
 */
@Entity
@Table(name = "cavgo_trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CavgoTripEntity {

    @Id
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "status", nullable = false)
    private String status;

    /** Last lifecycle event applied: created, started, updated, completed, cancelled, deleted. */
    @Column(name = "event")
    private String event;

    @Column(name = "route_id")
    private Long routeId;

    @Column(name = "vehicle_id")
    private Long vehicleId;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "driver_name")
    private String driverName;

    @Column(name = "driver_phone")
    private String driverPhone;

    @Column(name = "departure_time")
    private Instant departureTime;

    @Column(name = "completion_time")
    private Instant completionTime;

    @Column(name = "connection_mode")
    private String connectionMode;

    @Column(name = "notes")
    private String notes;

    @Column(name = "seats")
    private Integer seats;

    @Column(name = "remaining_seats")
    private Integer remainingSeats;

    @Column(name = "price")
    private Double price;

    @Column(name = "remaining_time_to_destination")
    private Long remainingTimeToDestination;

    @Column(name = "remaining_distance_to_destination")
    private Double remainingDistanceToDestination;

    @Column(name = "is_reversed")
    private Boolean isReversed;

    @Column(name = "current_speed")
    private Double currentSpeed;

    @Column(name = "current_latitude")
    private Double currentLatitude;

    @Column(name = "current_longitude")
    private Double currentLongitude;

    @Column(name = "has_custom_waypoints")
    private Boolean hasCustomWaypoints;

    @Column(name = "auto_return")
    private Boolean autoReturn;

    /** Full vehicle snapshot from the trip service (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_json", columnDefinition = "jsonb")
    private String vehicleJson;

    /** Full route snapshot from the trip service (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "route_json", columnDefinition = "jsonb")
    private String routeJson;

    /** Full waypoint list from the trip service (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "waypoints_json", columnDefinition = "jsonb")
    private String waypointsJson;

    @Column(name = "trip_created_at")
    private Instant tripCreatedAt;

    @Column(name = "trip_updated_at")
    private Instant tripUpdatedAt;

    /** When the event was consumed by this service. */
    @Column(name = "event_timestamp")
    private Instant eventTimestamp;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When to auto-delete this trip record (terminal status + 10 hours). */
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