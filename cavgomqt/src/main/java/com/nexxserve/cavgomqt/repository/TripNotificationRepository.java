package com.nexxserve.cavgomqt.repository;

import com.nexxserve.cavgomqt.entity.TripNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TripNotificationRepository extends JpaRepository<TripNotificationEntity, Long> {

    /**
     * Find notification by trip ID
     */
    Optional<TripNotificationEntity> findByTripId(Integer tripId);

    /**
     * Check if notification exists for a trip
     */
    boolean existsByTripId(Integer tripId);

    /**
     * Delete notification by trip ID
     */
    void deleteByTripId(Integer tripId);
}




