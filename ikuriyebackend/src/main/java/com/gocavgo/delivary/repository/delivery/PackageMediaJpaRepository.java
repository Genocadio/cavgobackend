package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageMediaJpaRepository extends JpaRepository<PackageMediaEntity, UUID> {
    List<PackageMediaEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageMediaEntity> findByPackageIdIn(Collection<UUID> packageIds);
    List<PackageMediaEntity> findByStorageMode(String storageMode);
    void deleteByPackageId(UUID packageId);
}
