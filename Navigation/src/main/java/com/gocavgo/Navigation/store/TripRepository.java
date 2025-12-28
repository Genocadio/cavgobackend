package com.gocavgo.Navigation.store;

import com.gocavgo.Navigation.model.Trip;
import com.gocavgo.Navigation.model.enums.TripStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByCarIdAndStatus(String carId, TripStatus status);
    
    // Find most recent active/created trip for a car
    @Query("SELECT t FROM Trip t WHERE t.carId = :carId AND t.status IN :statuses ORDER BY t.createdAt DESC")
    Optional<Trip> findMostRecentByCarIdAndStatuses(@Param("carId") String carId, @Param("statuses") List<TripStatus> statuses);
    
    List<Trip> findByCarId(String carId);
    
    List<Trip> findByStatus(TripStatus status);
    
    // Pagination support - findAll is already available from JpaRepository
    Page<Trip> findAll(Pageable pageable);
    
    Page<Trip> findByCarId(String carId, Pageable pageable);
    
    Page<Trip> findByStatus(TripStatus status, Pageable pageable);
}

