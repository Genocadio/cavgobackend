package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageEventJpaRepository extends JpaRepository<PackageEventEntity, UUID> {
    List<PackageEventEntity> findByPackageIdOrderByCreatedAtAsc(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackageEventEntity> findByPackageIdInOrderByCreatedAtAsc(Collection<UUID> packageIds);
    void deleteByPackageId(UUID packageId);
}
