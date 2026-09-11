package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.DriverRequestStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * A request from a normal user to become a driver for a specific company.
 * The fleet manager approves or rejects the request. On approval, the user's
 * role is updated to DRIVER in Nexxauth.
 */
@Entity
@Table(name = "driver_requests")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class DriverRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Nexxauth user id of the user requesting to be a driver. */
    @Column(name = "nexxauth_user_id", nullable = false)
    private Long nexxauthUserId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    /** The company code the user wants to join. */
    @Column(name = "company_code", nullable = false)
    private String companyCode;

    /** Resolved company id (set when request is created). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DriverRequestStatus status = DriverRequestStatus.PENDING;

    /** Rejection reason (set when rejected). */
    @Column(name = "rejection_reason")
    private String rejectionReason;
}
