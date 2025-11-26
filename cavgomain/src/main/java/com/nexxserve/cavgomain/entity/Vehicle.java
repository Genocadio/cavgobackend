package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.VehicleType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "vehicles")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Vehicle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Column(name = "make", nullable = false)
    private String make;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "device_public_key", columnDefinition = "TEXT")
    @Setter(AccessLevel.NONE)
    private String pubKey;

    private Instant keysetTime;

    @Column(name = "license_plate", unique = true)
    private String licensePlate;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type")
    private VehicleType vehicleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VehicleStatus status = VehicleStatus.AVAILABLE;

    @Column(name = "password_hash")
    private String passwordHash;


    @OneToMany(mappedBy = "vehicle", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<VehicleAssignment> assignments = new ArrayList<>();

    @OneToOne(mappedBy = "vehicle", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private VehicleSettings settings;

    @Column(name = "last_online_at")
    private java.time.LocalDateTime lastOnlineAt;

    public void setPubKey(String pubKey) {
        // Only update keysetTime if pubKey actually changes
        if (pubKey != null && !pubKey.equals(this.pubKey)) {
            this.keysetTime = Instant.now();
        }
        this.pubKey = pubKey;
    }

    public boolean isOnline() {
        if (lastOnlineAt == null) {
            return false;
        }
        // Consider vehicle online if last update was within 30 minutes
        return lastOnlineAt.isAfter(java.time.LocalDateTime.now().minusMinutes(30));
    }
}