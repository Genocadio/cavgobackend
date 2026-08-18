package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PackageAssignmentJpaRepository extends JpaRepository<PackageAssignmentEntity, UUID> {
    List<PackageAssignmentEntity> findByPackageIdOrderByAssignedAtDesc(UUID packageId);
    List<PackageAssignmentEntity> findByDriverId(Long driverId);
}
