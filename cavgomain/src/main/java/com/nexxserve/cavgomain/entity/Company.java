package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.CompanyStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "companies")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Company extends BaseEntity {

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "city")
    private String city;

    @Column(name = "company_code", nullable = false, unique = true)
    private String companyCode;


    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @OneToMany(mappedBy = "company", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<CompanyUser> users = new ArrayList<>();

    @OneToMany(mappedBy = "company", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @ToString.Exclude
    private List<Vehicle> vehicles = new ArrayList<>();
}
