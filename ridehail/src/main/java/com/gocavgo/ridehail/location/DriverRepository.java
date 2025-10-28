package com.gocavgo.ridehail.location;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    @Query(value = "SELECT d.* FROM drivers d WHERE d.is_available = true AND d.current_location IS NOT NULL AND ST_DWithin(d.current_location, ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography, :radius) ORDER BY d.current_location <-> ST_SetSRID(ST_MakePoint(:lon,:lat),4326)::geography LIMIT 1", nativeQuery = true)
    Optional<Driver> findNearestAvailable(@Param("lat") double lat, @Param("lon") double lon, @Param("radius") double radiusMeters);
}


