package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.PackageCarrierLegEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PackageCarrierLegJpaRepository extends JpaRepository<PackageCarrierLegEntity, UUID> {

    List<PackageCarrierLegEntity> findByPackageIdOrderByLinkedAtAsc(UUID packageId);

    List<PackageCarrierLegEntity> findByPackageIdOrderByLinkedAtDesc(UUID packageId);

    List<PackageCarrierLegEntity> findAllByPackageIdIn(Collection<UUID> packageIds);

    List<PackageCarrierLegEntity> findByDriverId(Long driverId);

    List<PackageCarrierLegEntity> findByTripId(Long tripId);

    List<PackageCarrierLegEntity> findByCompanyRefId(Long companyRefId);
}
