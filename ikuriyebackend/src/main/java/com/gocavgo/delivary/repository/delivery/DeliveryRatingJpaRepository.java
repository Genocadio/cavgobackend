package com.gocavgo.delivary.repository.delivery;

import com.gocavgo.delivary.entity.delivery.DeliveryRatingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRatingJpaRepository extends JpaRepository<DeliveryRatingEntity, UUID> {

    boolean existsByPackageIdAndClientId(UUID packageId, Long clientId);

    Optional<DeliveryRatingEntity> findByPackageIdAndClientId(UUID packageId, Long clientId);

    void deleteByPackageId(UUID packageId);

    @Query("select coalesce(avg(r.score), 0) from DeliveryRatingEntity r where r.driverId = :driverId")
    Double averageScoreByDriverId(@Param("driverId") Long driverId);

    @Query("select count(r) from DeliveryRatingEntity r where r.driverId = :driverId")
    long countByDriverId(@Param("driverId") Long driverId);
}
