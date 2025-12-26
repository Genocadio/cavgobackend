package com.nexxserve.cavgomqt.repository;

import com.nexxserve.cavgomqt.entity.NavigaTripEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Repository for managing Naviga trip registry.
 * Tracks which cars have active trips in the Naviga navigation service.
 */
@Repository
public interface NavigaTripRepository extends JpaRepository<NavigaTripEntity, Long> {

    /**
     * Find active trip by car ID
     */
    Optional<NavigaTripEntity> findByCarId(String carId);

    /**
     * Find active trip by trip ID
     */
    Optional<NavigaTripEntity> findByTripId(Long tripId);

    /**
     * Check if car has an active trip
     */
    boolean existsByCarId(String carId);

    /**
     * Check if trip ID exists
     */
    boolean existsByTripId(Long tripId);

    /**
     * Delete trip by car ID
     */
    @Transactional
    void deleteByCarId(String carId);

    /**
     * Delete trip by trip ID
     */
    @Transactional
    void deleteByTripId(Long tripId);
}
