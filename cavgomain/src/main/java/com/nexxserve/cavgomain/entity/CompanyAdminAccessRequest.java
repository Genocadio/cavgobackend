package com.nexxserve.cavgomain.entity;

import com.nexxserve.cavgomain.enums.CompanyAdminAccessRequestStatus;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(
        name = "company_admin_access_requests",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_company_admin_request_user_company",
                columnNames = {"nexxauth_user_id", "company_id"}
        )
)
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CompanyAdminAccessRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nexxauth_user_id", nullable = false)
    private Long nexxauthUserId;

    @Column(name = "company_code", nullable = false)
    private String companyCode;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CompanyAdminAccessRequestStatus status = CompanyAdminAccessRequestStatus.PENDING;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private java.time.LocalDateTime approvedAt;

    @Column(name = "rejected_by")
    private Long rejectedBy;

    @Column(name = "rejected_at")
    private java.time.LocalDateTime rejectedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;
}
