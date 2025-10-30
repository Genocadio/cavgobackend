package com.nexxserve.cavgomain.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "vehicle_settings")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class VehicleSettings extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false, unique = true)
    @ToString.Exclude
    private Vehicle vehicle;

    @Column(name = "logout", nullable = false)
    private Boolean logout = true;

    @Column(name = "devmode", nullable = false)
    private Boolean devmode = false;

    @Column(name = "deactivate", nullable = false)
    private Boolean deactivate = false;

    @Column(name = "appmode", nullable = false)
    private Boolean appmode = false;

    @Column(name = "simulate", nullable = false)
    private Boolean simulate = false;
}

