package com.gocavgo.ridehail.trip;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

import java.time.OffsetDateTime;

@Entity
@Table(name = "trips")
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "passenger_id", nullable = false)
    private Long passengerId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TripStatus status;

    @Column(name = "origin", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point origin;

    @Column(name = "destination", nullable = false, columnDefinition = "geography(Point,4326)")
    private Point destination;

    // distances in meters, ETAs in seconds
    @Column(name = "driver_to_pickup_meters")
    private Double driverToPickupMeters;

    @Column(name = "origin_to_destination_meters")
    private Double originToDestinationMeters;

    @Column(name = "driver_to_pickup_eta_seconds")
    private Integer driverToPickupEtaSeconds;

    @Column(name = "origin_to_destination_eta_seconds")
    private Integer originToDestinationEtaSeconds;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @PreUpdate
    public void preUpdate() { this.updatedAt = OffsetDateTime.now(); }

    public Long getId() { return id; }
    public Long getPassengerId() { return passengerId; }
    public void setPassengerId(Long passengerId) { this.passengerId = passengerId; }
    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }
    public TripStatus getStatus() { return status; }
    public void setStatus(TripStatus status) { this.status = status; }
    public Point getOrigin() { return origin; }
    public void setOrigin(Point origin) { this.origin = origin; }
    public Point getDestination() { return destination; }
    public void setDestination(Point destination) { this.destination = destination; }
    public Double getDriverToPickupMeters() { return driverToPickupMeters; }
    public void setDriverToPickupMeters(Double driverToPickupMeters) { this.driverToPickupMeters = driverToPickupMeters; }
    public Double getOriginToDestinationMeters() { return originToDestinationMeters; }
    public void setOriginToDestinationMeters(Double originToDestinationMeters) { this.originToDestinationMeters = originToDestinationMeters; }
    public Integer getDriverToPickupEtaSeconds() { return driverToPickupEtaSeconds; }
    public void setDriverToPickupEtaSeconds(Integer driverToPickupEtaSeconds) { this.driverToPickupEtaSeconds = driverToPickupEtaSeconds; }
    public Integer getOriginToDestinationEtaSeconds() { return originToDestinationEtaSeconds; }
    public void setOriginToDestinationEtaSeconds(Integer originToDestinationEtaSeconds) { this.originToDestinationEtaSeconds = originToDestinationEtaSeconds; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}


