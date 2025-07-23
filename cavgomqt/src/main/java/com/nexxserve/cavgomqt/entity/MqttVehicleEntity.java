package com.nexxserve.cavgomqt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "mqtt_vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MqttVehicleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", nullable = false, unique = true)
    private Long vehicleId; // Reference to backend vehicle ID

    @Column(name = "license_plate", nullable = false)
    private String licensePlate;

    @Column(name = "make")
    private String make;

    @Column(name = "model")
    private String model;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "vehicle_type")
    private String vehicleType;

    @Column(name = "is_online", nullable = false)
    private Boolean isOnline = false;

    @Column(name = "last_heartbeat")
    private Long lastHeartbeat = 0L;

    @Column(name = "current_trip_id")
    private String currentTripId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;


    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return "MqttVehicleEntity{" +
                "id=" + id +
                ", vehicleId=" + vehicleId +
                ", licensePlate='" + licensePlate + '\'' +
                ", isOnline=" + isOnline +
                ", currentTripId='" + currentTripId + '\'' +
                '}';
    }
}