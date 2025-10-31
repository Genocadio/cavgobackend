package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.VehicleLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleLocationRepository extends JpaRepository<VehicleLocation, Long> {
    
    List<VehicleLocation> findByVehicleIdAndRecordedAtAfter(Long vehicleId, LocalDateTime since);
    
    Optional<VehicleLocation> findTopByVehicleIdOrderByRecordedAtDesc(Long vehicleId);
    
    @Modifying
    @Query("DELETE FROM VehicleLocation vl WHERE vl.recordedAt < :cutoffTime")
    int deleteByRecordedAtBefore(@Param("cutoffTime") LocalDateTime cutoffTime);
}



