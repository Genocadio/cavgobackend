package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    List<Vehicle> findByCompanyId(Long companyId);
    List<Vehicle> findByStatus(VehicleStatus status);
    List<Vehicle> findByVehicleType(VehicleType vehicleType);
    List<Vehicle> findByCompanyIdAndStatus(Long companyId, VehicleStatus status);
    Optional<Vehicle> findByLicensePlate(String licensePlate);

    boolean existsByLicensePlate(String licensePlate);

    @Query("SELECT v FROM Vehicle v WHERE v.company.id = :companyId AND v.status = 'AVAILABLE'")
    List<Vehicle> findAvailableVehiclesByCompany(@Param("companyId") Long companyId);

    // Fetch vehicles with their active assignments
    // Note: We fetch the vehicle first, then filter assignments in the service layer if needed
    @Query("SELECT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.id = :id")
    Optional<Vehicle> findByIdWithActiveAssignment(@Param("id") Long id);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.company.id = :companyId")
    List<Vehicle> findByCompanyIdWithActiveAssignments(@Param("companyId") Long companyId);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va")
    List<Vehicle> findAllWithActiveAssignments();

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va " +
           "WHERE (v.createdAt >= :timeLimit OR v.updatedAt >= :timeLimit)")
    List<Vehicle> findAllWithActiveAssignmentsAfterTime(@Param("timeLimit") LocalDateTime timeLimit);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va " +
           "WHERE v.company.id = :companyId AND (v.createdAt >= :timeLimit OR v.updatedAt >= :timeLimit)")
    List<Vehicle> findByCompanyIdWithActiveAssignmentsAfterTime(@Param("companyId") Long companyId, @Param("timeLimit") LocalDateTime timeLimit);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.licensePlate = :licensePlate")
    Optional<Vehicle> findByLicensePlateWithActiveAssignment(@Param("licensePlate") String licensePlate);
}
