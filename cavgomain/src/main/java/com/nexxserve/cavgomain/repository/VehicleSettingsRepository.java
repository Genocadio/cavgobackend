package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.VehicleSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleSettingsRepository extends JpaRepository<VehicleSettings, Long> {
    Optional<VehicleSettings> findByVehicleId(Long vehicleId);
}







