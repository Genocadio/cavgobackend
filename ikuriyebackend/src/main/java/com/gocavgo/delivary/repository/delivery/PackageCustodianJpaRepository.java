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

    /**
     * Returns package IDs where the given user is the *current* (most recent)
     * custodian.  Only one custodian row per package is the latest; if a user
     * was replaced by someone else this query will NOT return that package.
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT DISTINCT pc.package_id FROM package_custodians pc " +
                "INNER JOIN ( " +
                "  SELECT package_id, MAX(assigned_at) AS max_at " +
                "  FROM package_custodians GROUP BY package_id " +
                ") latest ON pc.package_id = latest.package_id AND pc.assigned_at = latest.max_at " +
                "WHERE pc.user_id = :userId",
        nativeQuery = true)
    List<UUID> findCurrentCustodianPackageIdsByUserId(@org.springframework.data.jpa.repository.Param("userId") Long userId);
}
