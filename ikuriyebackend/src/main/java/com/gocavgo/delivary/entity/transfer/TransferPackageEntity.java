package com.gocavgo.delivary.entity.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfer_packages",
        uniqueConstraints = @UniqueConstraint(name = "uq_transfer_package",
                columnNames = {"transfer_id", "package_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferPackageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "added_by", nullable = false)
    private Long addedBy;

    @Column(name = "added_at", nullable = false, updatable = false)
    private Instant addedAt;
}
