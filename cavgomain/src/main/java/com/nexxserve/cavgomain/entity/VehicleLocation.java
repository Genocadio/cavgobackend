package com.nexxserve.cavgomain.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "vehicle_locations", indexes = {
    @Index(name = "idx_recorded_at", columnList = "recorded_at")
})
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class VehicleLocation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    @ToString.Exclude
    private Vehicle vehicle;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "speed")
    private Double speed; // in meters per second, nullable

    @Column(name = "accuracy")
    private Double accuracy; // in meters, nullable

    @Column(name = "bearing")
    private Double bearing; // in degrees, nullable

    @Column(name = "timestamp", nullable = false)
    private Long timestamp; // when the location was recorded (milliseconds since epoch)

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt; // when we received this location
}

