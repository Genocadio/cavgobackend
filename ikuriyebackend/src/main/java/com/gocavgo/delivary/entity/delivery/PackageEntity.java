package com.gocavgo.delivary.entity.delivery;

import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageOrigin;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "packages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PackageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tracking_code", nullable = false, unique = true)
    private String trackingCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_type", nullable = false)
    private DeliveryType deliveryType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PackageStatus status;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "company_id")
    private UUID companyId;

    /**
     * Numeric cavgomain company id ({@code companies.id}), also used by
     * cavgotrips and adminaggregate. This — not {@link #companyId} — is the key
     * for company-scoped package reporting. See {@code docs/COMPANY_IDS.md}.
     */
    @Column(name = "company_ref_id")
    private Long companyRefId;

    @Column(name = "trip_id")
    private Long tripId;

    /** PRIVATE until a driver with an active trip takes custody of the package. */
    @Enumerated(EnumType.STRING)
    @Column(name = "package_origin", nullable = false)
    @Builder.Default
    private PackageOrigin packageOrigin = PackageOrigin.PRIVATE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
