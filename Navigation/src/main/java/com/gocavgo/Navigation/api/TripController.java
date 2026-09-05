package com.gocavgo.Navigation.api;

import com.gocavgo.Navigation.exception.OsrmUnavailableException;
import com.gocavgo.Navigation.model.dto.TripCreateRequest;
import com.gocavgo.Navigation.model.dto.TripResponse;
import com.gocavgo.Navigation.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
@Slf4j
public class TripController {
    private final TripService tripService;

    @PostMapping
    public ResponseEntity<TripResponse> createTrip(@RequestBody TripCreateRequest request) {
        try {
            log.info(
                    "Creating trip for carId: {}, waypoints: {}, includeInstructions: {}, includeOrigin: {}, isCityTrip: {}",
                    request.getCarId(), request.getWaypoints().size(),
                    request.getIncludeInstructions(), request.getIncludeOrigin(), request.getIsCityTrip());

            TripResponse response = tripService.createTrip(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Invalid trip creation request", e);
            return ResponseEntity.badRequest().build();
        } catch (OsrmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating trip", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<TripResponse> getTrip(
            @PathVariable Long tripId,
            @RequestParam(required = false, defaultValue = "false") boolean render) {
        try {
            log.info("Getting trip {} with render={}", tripId, render);
            TripResponse response = tripService.getTripById(tripId, render);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Trip not found: {}", tripId, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting trip {}", tripId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{tripId}")
    public ResponseEntity<Void> deleteTrip(@PathVariable Long tripId) {
        try {
            log.info("Deleting trip {}", tripId);
            tripService.deleteTrip(tripId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Trip not found: {}", tripId, e);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error deleting trip {}", tripId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllTrips(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(required = false, defaultValue = "DESC") String sortDir,
            @RequestParam(required = false, defaultValue = "false") boolean render) {
        try {
            log.info("Getting all trips - page: {}, size: {}, sortBy: {}, sortDir: {}, render: {}",
                    page, size, sortBy, sortDir, render);

            Page<com.gocavgo.Navigation.model.dto.TripDto> tripsPage = tripService.getAllTrips(
                    page, size, sortBy, sortDir, render);

            Map<String, Object> response = new HashMap<>();
            response.put("trips", tripsPage.getContent());
            response.put("currentPage", tripsPage.getNumber());
            response.put("totalItems", tripsPage.getTotalElements());
            response.put("totalPages", tripsPage.getTotalPages());
            response.put("pageSize", tripsPage.getSize());
            response.put("hasNext", tripsPage.hasNext());
            response.put("hasPrevious", tripsPage.hasPrevious());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting all trips", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
