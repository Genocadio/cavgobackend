package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageAssignmentJpaRepository extends JpaRepository<PackageAssignmentEntity, UUID> {
    List<PackageAssignmentEntity> findByPackageIdOrderByAssignedAtDesc(UUID packageId);
    List<PackageAssignmentEntity> findByPackageIdInOrderByAssignedAtDesc(Collection<UUID> packageIds);
    Optional<PackageAssignmentEntity> findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(UUID packageId, Long driverId);
    List<PackageAssignmentEntity> findByDriverId(Long driverId);
    void deleteByPackageId(UUID packageId);
}
