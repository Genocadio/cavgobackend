package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackagePersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackagePersonJpaRepository extends JpaRepository<PackagePersonEntity, UUID> {
    List<PackagePersonEntity> findByPackageId(UUID packageId);

    /** Batch variant used by list endpoints to avoid N+1 queries. */
    List<PackagePersonEntity> findByPackageIdIn(Collection<UUID> packageIds);

    List<UUID> findUserIdsByPackageId(UUID packageId);
    List<PackagePersonEntity> findByUserId(Long userId);
    List<PackagePersonEntity> findByPhone(String phone);
    void deleteByPackageId(UUID packageId);
}
