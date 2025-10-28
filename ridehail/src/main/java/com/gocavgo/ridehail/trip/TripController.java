package com.gocavgo.ridehail.trip;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
public class TripController {
    private final TripRepository tripRepository;
    private final com.gocavgo.ridehail.location.DriverRepository driverRepository;

    public TripController(TripRepository tripRepository, com.gocavgo.ridehail.location.DriverRepository driverRepository) {
        this.tripRepository = tripRepository;
        this.driverRepository = driverRepository;
    }

    @GetMapping("/trips/{id}")
    public ResponseEntity<?> getTrip(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id)
                .filter(t -> t.getPassengerId().equals(userId) || t.getDriverId().equals(userId))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }

    @GetMapping("/trips/active")
    public ResponseEntity<?> getActive(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        var statuses = List.of(TripStatus.DRIVER_ASSIGNED, TripStatus.EN_ROUTE, TripStatus.AT_PICKUP, TripStatus.IN_PROGRESS);
        return tripRepository.findFirstByDriverIdAndStatusIn(userId, statuses)
                .or(() -> tripRepository.findFirstByPassengerIdAndStatusIn(userId, statuses))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/trips/{id}/accept")
    public ResponseEntity<?> accept(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id).map(t -> {
            if (!t.getDriverId().equals(userId)) return ResponseEntity.status(403).body("Forbidden");
            t.setStatus(TripStatus.EN_ROUTE);
            t.setUpdatedAt(OffsetDateTime.now());
            return ResponseEntity.ok(tripRepository.save(t));
        }).orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }

    @PostMapping("/trips/{id}/arrive-pickup")
    public ResponseEntity<?> arrivePickup(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id).map(t -> {
            if (!t.getDriverId().equals(userId)) return ResponseEntity.status(403).body("Forbidden");
            t.setStatus(TripStatus.AT_PICKUP);
            t.setUpdatedAt(OffsetDateTime.now());
            return ResponseEntity.ok(tripRepository.save(t));
        }).orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }

    @PostMapping("/trips/{id}/start")
    public ResponseEntity<?> start(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id).map(t -> {
            if (!t.getDriverId().equals(userId)) return ResponseEntity.status(403).body("Forbidden");
            t.setStatus(TripStatus.IN_PROGRESS);
            t.setStartedAt(OffsetDateTime.now());
            t.setUpdatedAt(OffsetDateTime.now());
            return ResponseEntity.ok(tripRepository.save(t));
        }).orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }

    @PostMapping("/trips/{id}/complete")
    public ResponseEntity<?> complete(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id).map(t -> {
            if (!t.getDriverId().equals(userId)) return ResponseEntity.status(403).body("Forbidden");
            t.setStatus(TripStatus.COMPLETED);
            t.setCompletedAt(OffsetDateTime.now());
            t.setUpdatedAt(OffsetDateTime.now());
            var saved = tripRepository.save(t);
            driverRepository.findById(t.getDriverId()).ifPresent(d -> { d.setAvailable(true); driverRepository.save(d); });
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }

    @PostMapping("/trips/{id}/cancel")
    public ResponseEntity<?> cancel(Authentication auth, @PathVariable Long id) {
        Long userId = (Long) auth.getPrincipal();
        return tripRepository.findById(id).map(t -> {
            if (!t.getPassengerId().equals(userId) && !t.getDriverId().equals(userId)) return ResponseEntity.status(403).body("Forbidden");
            t.setStatus(TripStatus.CANCELED);
            t.setUpdatedAt(OffsetDateTime.now());
            var saved = tripRepository.save(t);
            driverRepository.findById(t.getDriverId()).ifPresent(d -> { d.setAvailable(true); driverRepository.save(d); });
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.status(404).body("Not found"));
    }
}


