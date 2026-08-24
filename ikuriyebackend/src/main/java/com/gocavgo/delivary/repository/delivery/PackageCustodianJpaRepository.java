package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageCustodianJpaRepository extends JpaRepository<PackageCustodianEntity, UUID> {
    List<PackageCustodianEntity> findByPackageId(UUID packageId);

    List<UUID> findUserIdsByPackageId(UUID packageId);
    List<PackageCustodianEntity> findByUserId(Long userId);
    List<PackageCustodianEntity> findByPackageIdAndRole(UUID packageId, CustodianRole role);
    Optional<PackageCustodianEntity> findByPackageIdAndUserIdAndRole(UUID packageId, Long userId, CustodianRole role);
    Optional<PackageCustodianEntity> findTopByPackageIdOrderByAssignedAtDesc(UUID packageId);
    List<PackageCustodianEntity> findByUserIdAndRole(Long userId, CustodianRole role);
    void deleteByPackageId(UUID packageId);
}
