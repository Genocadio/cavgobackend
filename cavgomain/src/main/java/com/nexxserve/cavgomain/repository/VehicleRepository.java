package com.nexxserve.cavgomain.repository;

import com.nexxserve.cavgomain.entity.Vehicle;
import com.nexxserve.cavgomain.enums.VehicleStatus;
import com.nexxserve.cavgomain.enums.VehicleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.id = :id AND (va.status = 'ACTIVE' OR va IS NULL)")
    Optional<Vehicle> findByIdWithActiveAssignment(@Param("id") Long id);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.company.id = :companyId AND (va.status = 'ACTIVE' OR va IS NULL)")
    List<Vehicle> findByCompanyIdWithActiveAssignments(@Param("companyId") Long companyId);

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE (va.status = 'ACTIVE' OR va IS NULL)")
    List<Vehicle> findAllWithActiveAssignments();

    @Query("SELECT DISTINCT v FROM Vehicle v LEFT JOIN FETCH v.assignments va WHERE v.licensePlate = :licensePlate AND (va.status = 'ACTIVE' OR va IS NULL)")
    Optional<Vehicle> findByLicensePlateWithActiveAssignment(@Param("licensePlate") String licensePlate);
}
