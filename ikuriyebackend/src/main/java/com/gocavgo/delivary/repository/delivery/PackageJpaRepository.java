package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageJpaRepository extends JpaRepository<PackageEntity, UUID> {
    Optional<PackageEntity> findByTrackingCode(String trackingCode);
    List<PackageEntity> findByCreatorId(Long creatorId, Sort sort);
    Page<PackageEntity> findByCreatorId(Long creatorId, Pageable pageable);
    List<PackageEntity> findByStatus(PackageStatus status, Sort sort);
    Page<PackageEntity> findByStatus(PackageStatus status, Pageable pageable);
}
