package com.gocavgo.ridehail.match;

import com.gocavgo.ridehail.location.Driver;
import com.gocavgo.ridehail.geo.GeoUtil;
import com.gocavgo.ridehail.location.DriverRepository;
import com.gocavgo.ridehail.trip.Trip;
import com.gocavgo.ridehail.trip.TripRepository;
import com.gocavgo.ridehail.trip.TripStatus;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MatchingService {
    private static final GeometryFactory GEOM_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private final DriverRepository driverRepository;
    private final TripRepository tripRepository;
    private final EntityManager entityManager;

    public MatchingService(DriverRepository driverRepository, TripRepository tripRepository, EntityManager entityManager) {
        this.driverRepository = driverRepository;
        this.tripRepository = tripRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public Optional<Trip> requestRide(Long passengerId, double originLat, double originLon, double destLat, double destLon, double radiusMeters) {
        // Simple pre-check: passenger not already in active trip
        if (tripRepository.findFirstByPassengerIdAndStatusIn(passengerId, activeStatuses()).isPresent()) {
            return Optional.empty();
        }

        var nearestOpt = driverRepository.findNearestAvailable(originLat, originLon, radiusMeters);
        if (nearestOpt.isEmpty()) return Optional.empty();
        Driver driver = nearestOpt.get();

        // Mark driver unavailable and persist
        driver.setAvailable(false);
        driverRepository.save(driver);

        Point origin = GEOM_FACTORY.createPoint(new Coordinate(originLon, originLat));
        origin.setSRID(4326);
        Point destination = GEOM_FACTORY.createPoint(new Coordinate(destLon, destLat));
        destination.setSRID(4326);

        Trip trip = new Trip();
        trip.setPassengerId(passengerId);
        trip.setDriverId(driver.getUserId());
        trip.setStatus(TripStatus.DRIVER_ASSIGNED);
        trip.setOrigin(origin);
        trip.setDestination(destination);
        // compute straight-line distances and simple ETAs
        if (driver.getCurrentLocation() != null) {
            double d2p = GeoUtil.haversineMeters(originLat, originLon, driver.getCurrentLocation().getY(), driver.getCurrentLocation().getX());
            trip.setDriverToPickupMeters(d2p);
            trip.setDriverToPickupEtaSeconds(GeoUtil.etaSeconds(d2p, 8.33)); // ~30 km/h
        }
        double o2d = GeoUtil.haversineMeters(originLat, originLon, destLat, destLon);
        trip.setOriginToDestinationMeters(o2d);
        trip.setOriginToDestinationEtaSeconds(GeoUtil.etaSeconds(o2d, 11.11)); // ~40 km/h
        trip.setCreatedAt(OffsetDateTime.now());
        trip.setUpdatedAt(OffsetDateTime.now());
        Trip saved = tripRepository.save(trip);
        return Optional.of(saved);
    }

    private List<TripStatus> activeStatuses() {
        return List.of(TripStatus.DRIVER_ASSIGNED, TripStatus.EN_ROUTE, TripStatus.AT_PICKUP, TripStatus.IN_PROGRESS);
    }
}


