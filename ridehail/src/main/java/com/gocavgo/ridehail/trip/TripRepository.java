package com.gocavgo.ridehail.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findFirstByDriverIdAndStatusIn(Long driverId, Iterable<TripStatus> statuses);
    Optional<Trip> findFirstByPassengerIdAndStatusIn(Long passengerId, Iterable<TripStatus> statuses);
}


