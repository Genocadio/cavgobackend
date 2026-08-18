package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.DeliveryCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryCodeJpaRepository extends JpaRepository<DeliveryCodeEntity, UUID> {
    Optional<DeliveryCodeEntity> findByPackageId(UUID packageId);
}
