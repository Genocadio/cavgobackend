package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageLocationJpaRepository extends JpaRepository<PackageLocationEntity, UUID> {
    List<PackageLocationEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageLocationEntity> findByPackageIdIn(Collection<UUID> packageIds);
    void deleteByPackageId(UUID packageId);
}
