package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.enums.delivery.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageLocationJpaRepository extends JpaRepository<PackageLocationEntity, UUID> {
    List<PackageLocationEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageLocationEntity> findByPackageIdIn(Collection<UUID> packageIds);

    /**
     * Package IDs whose destination office matches one of the worker's office
     * location IDs. Used by the destination-office intake queue.
     */
    @Query("SELECT pl.packageId FROM PackageLocationEntity pl "
            + "WHERE pl.type = :type AND pl.officeLocationId IN :officeLocationIds")
    List<UUID> findPackageIdsByTypeAndOfficeLocationIdIn(
            @Param("type") LocationType type,
            @Param("officeLocationIds") Collection<UUID> officeLocationIds);

    /** Batch destination-office validation for a worker claim. */
    List<PackageLocationEntity> findByTypeAndOfficeLocationIdInAndPackageIdIn(
            LocationType type, Collection<UUID> officeLocationIds, Collection<UUID> packageIds);

    void deleteByPackageId(UUID packageId);
}
