package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficeClaimJpaRepository extends JpaRepository<OfficeClaimEntity, UUID> {

    Optional<OfficeClaimEntity> findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(UUID packageId, Long driverId);

    Optional<OfficeClaimEntity> findFirstByPackageIdOrderByClaimedAtDesc(UUID packageId);

    List<OfficeClaimEntity> findByPackageIdInOrderByClaimedAtDesc(Collection<UUID> packageIds);
}
