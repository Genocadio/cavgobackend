package com.nexxserve.cavgomqt.repository;

import com.nexxserve.cavgomqt.entity.MqttVehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MqttVehicleRepository extends JpaRepository<MqttVehicleEntity, Long> {

    /**
     * Find vehicle by backend vehicle ID
     */
    Optional<MqttVehicleEntity> findByVehicleId(Long vehicleId);


    /**
     * Check if vehicle exists by backend ID
     */
    boolean existsByVehicleId(Long vehicleId);

    /**
     * Find all available vehicles (online and not on trip)
     */
    @Query("SELECT v FROM MqttVehicleEntity v WHERE v.isOnline = true AND v.currentTripId IS NULL")
    List<MqttVehicleEntity> findAvailableVehicles();

    /**
     * Find all online vehicles
     */
    @Query("SELECT v FROM MqttVehicleEntity v WHERE v.isOnline = true")
    List<MqttVehicleEntity> findOnlineVehicles();

    /**
     * Find vehicles by trip ID
     */
    Optional<MqttVehicleEntity> findByCurrentTripId(String tripId);

    /**
     * Count vehicles by status
     */
    @Query("SELECT COUNT(v) FROM MqttVehicleEntity v WHERE v.isOnline = :online")
    Long countByOnlineStatus(@Param("online") Boolean online);

    /**
     * Count vehicles on trips
     */
    @Query("SELECT COUNT(v) FROM MqttVehicleEntity v WHERE v.currentTripId IS NOT NULL")
    Long countVehiclesOnTrip();

}