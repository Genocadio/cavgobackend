package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.CompanyUserRole;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CompanyUser extends User {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private CompanyUserRole role;

    @Column(name = "license_number")
    private String licenseNumber;

    @Column(name = "license_expiry")
    private java.time.LocalDate licenseExpiry;

    @OneToMany(mappedBy = "driver", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<VehicleAssignment> vehicleAssignments = new ArrayList<>();
}