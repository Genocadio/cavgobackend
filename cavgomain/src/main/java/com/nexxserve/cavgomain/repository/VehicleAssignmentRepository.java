package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.VehicleAssignment;
import com.nexxserve.cavgomain.enums.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleAssignmentRepository extends JpaRepository<VehicleAssignment, Long> {
    List<VehicleAssignment> findByVehicleId(Long vehicleId);
    List<VehicleAssignment> findByDriverId(Long driverId);
    List<VehicleAssignment> findByStatus(AssignmentStatus status);

    @Query("SELECT va FROM VehicleAssignment va WHERE va.vehicle.id = :vehicleId AND va.status = 'ACTIVE'")
    Optional<VehicleAssignment> findActiveAssignmentByVehicle(@Param("vehicleId") Long vehicleId);

    @Query("SELECT va FROM VehicleAssignment va WHERE va.driver.id = :driverId AND va.status = 'ACTIVE'")
    List<VehicleAssignment> findActiveAssignmentsByDriver(@Param("driverId") Long driverId);

    @Query("SELECT va FROM VehicleAssignment va WHERE va.assignedDate BETWEEN :startDate AND :endDate")
    List<VehicleAssignment> findAssignmentsByDateRange(@Param("startDate") LocalDateTime startDate,
                                                       @Param("endDate") LocalDateTime endDate);
}