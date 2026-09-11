package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.CompanyAccessRequestStatus;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A request from a staff user (typically a fleet manager) to gain access to a
 * company. The requesting user enters the company code; a different staff
 * member of that company must approve the request before the user is assigned
 * to the company. Self-approval is not allowed.
 */
@Entity
@Table(name = "company_access_requests")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CompanyAccessRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Nexxauth user id of the user requesting company access. */
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

    /** The role the requester carries (snapshot from Nexxauth roles). */
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private CompanyUserRole role;

    /** The company code the user wants to join. */
    @Column(name = "company_code", nullable = false)
    private String companyCode;

    /** Resolved company id (set when the request is created). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CompanyAccessRequestStatus status = CompanyAccessRequestStatus.PENDING;

    /** Rejection reason (set when rejected). */
    @Column(name = "rejection_reason")
    private String rejectionReason;

    /** Nexxauth user id of the staff member who approved the request. */
    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** Nexxauth user id of the staff member who rejected the request. */
    @Column(name = "rejected_by")
    private Long rejectedBy;
}