package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PackageLocationJpaRepository extends JpaRepository<PackageLocationEntity, UUID> {
    List<PackageLocationEntity> findByPackageId(UUID packageId);
    void deleteByPackageId(UUID packageId);
}
