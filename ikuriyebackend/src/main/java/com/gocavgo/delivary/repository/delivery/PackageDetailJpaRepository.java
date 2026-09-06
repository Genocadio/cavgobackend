package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageDetailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PackageDetailJpaRepository extends JpaRepository<PackageDetailEntity, UUID> {
    Optional<PackageDetailEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageDetailEntity> findByPackageIdIn(Collection<UUID> packageIds);
    void deleteByPackageId(UUID packageId);
}
