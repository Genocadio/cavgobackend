package com.gocavgo.ridehail.match;

import com.gocavgo.ridehail.trip.Trip;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

record RideRequest(@NotNull Double originLat, @NotNull Double originLon, @NotNull Double destLat, @NotNull Double destLon, Double radiusMeters) {}

@RestController
public class MatchController {
    private final MatchingService matchingService;

    public MatchController(MatchingService matchingService) {
        this.matchingService = matchingService;
    }

    @PostMapping("/rides/request")
    public ResponseEntity<?> requestRide(Authentication auth, @Valid @RequestBody RideRequest req) {
        Long passengerId = (Long) auth.getPrincipal();
        double radius = req.radiusMeters() == null ? 3000.0 : req.radiusMeters();
        return matchingService.requestRide(passengerId, req.originLat(), req.originLon(), req.destLat(), req.destLon(), radius)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body("No available drivers nearby"));
    }
}


