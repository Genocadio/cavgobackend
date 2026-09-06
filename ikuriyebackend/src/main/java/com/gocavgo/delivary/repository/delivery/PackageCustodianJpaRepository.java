package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageCustodianJpaRepository extends JpaRepository<PackageCustodianEntity, UUID> {
    List<PackageCustodianEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageCustodianEntity> findByPackageIdIn(Collection<UUID> packageIds);

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
        value = "SELECT pc.packageId FROM PackageCustodianEntity pc " +
                "WHERE pc.userId = :userId AND NOT EXISTS ( " +
                "  SELECT 1 FROM PackageCustodianEntity pc2 " +
                "  WHERE pc2.packageId = pc.packageId AND pc2.assignedAt > pc.assignedAt " +
                ")")
    List<UUID> findCurrentCustodianPackageIdsByUserId(@Param("userId") Long userId);

    /**
     * Returns package IDs whose *current* (most recent) custodian row has the
     * given role — e.g. role OFFICE for packages physically held at an office
     * (the row's userId is the worker/driver who recorded the handover).
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT pc.packageId FROM PackageCustodianEntity pc " +
                "WHERE pc.role = :role AND NOT EXISTS ( " +
                "  SELECT 1 FROM PackageCustodianEntity pc2 " +
                "  WHERE pc2.packageId = pc.packageId AND pc2.assignedAt > pc.assignedAt " +
                ")")
    List<UUID> findCurrentCustodianPackageIdsByRole(@Param("role") CustodianRole role);
}
