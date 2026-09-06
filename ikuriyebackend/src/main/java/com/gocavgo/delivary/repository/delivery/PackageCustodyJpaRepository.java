package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageCustodyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageCustodyJpaRepository extends JpaRepository<PackageCustodyEntity, UUID> {
    List<PackageCustodyEntity> findByPackageIdOrderByTimestampAsc(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageCustodyEntity> findByPackageIdInOrderByTimestampAsc(Collection<UUID> packageIds);
    void deleteByPackageId(UUID packageId);
}
