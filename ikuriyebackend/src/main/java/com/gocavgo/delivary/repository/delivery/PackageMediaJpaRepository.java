package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PackageMediaJpaRepository extends JpaRepository<PackageMediaEntity, UUID> {
    List<PackageMediaEntity> findByPackageId(UUID packageId);
    List<PackageMediaEntity> findByStorageMode(String storageMode);
    void deleteByPackageId(UUID packageId);
}
