package com.gocavgo.ridehail.location;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

record LocationUpdateRequest(@NotNull Double lat, @NotNull Double lon) {}

@RestController
public class LocationController {
    private static final GeometryFactory GEOM_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);
    private final DriverRepository driverRepository;
    private final PassengerRepository passengerRepository;

    public LocationController(DriverRepository driverRepository, PassengerRepository passengerRepository) {
        this.driverRepository = driverRepository;
        this.passengerRepository = passengerRepository;
    }

    @PostMapping("/drivers/location")
    public ResponseEntity<?> updateDriverLocation(Authentication auth, @Valid @RequestBody LocationUpdateRequest req) {
        Long userId = (Long) auth.getPrincipal();
        Point p = GEOM_FACTORY.createPoint(new Coordinate(req.lon(), req.lat()));
        p.setSRID(4326);
        Driver d = driverRepository.findById(userId).orElseGet(() -> {
            Driver nd = new Driver();
            nd.setUserId(userId);
            nd.setPlateNumber("");
            return nd;
        });
        d.setCurrentLocation(p);
        driverRepository.save(d);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/drivers/nearby")
    public ResponseEntity<?> nearby(@RequestParam double lat, @RequestParam double lon, @RequestParam(defaultValue = "3000") double radius) {
        return driverRepository.findNearestAvailable(lat, lon, radius)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/passengers/location")
    public ResponseEntity<?> updatePassengerLocation(Authentication auth, @Valid @RequestBody LocationUpdateRequest req) {
        Long userId = (Long) auth.getPrincipal();
        Point p = GEOM_FACTORY.createPoint(new Coordinate(req.lon(), req.lat()));
        p.setSRID(4326);
        Passenger pas = passengerRepository.findById(userId).orElseGet(() -> {
            Passenger np = new Passenger();
            np.setUserId(userId);
            return np;
        });
        pas.setCurrentLocation(p);
        passengerRepository.save(pas);
        return ResponseEntity.ok().build();
    }
}


