package com.gocavgo.Navigation.api;

import com.gocavgo.Navigation.model.dto.RouteCalculateRequest;
import com.gocavgo.Navigation.model.dto.RouteCalculateResponse;
import com.gocavgo.Navigation.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
@Slf4j
public class RouteController {
    private final RouteService routeService;

    @PostMapping("/calculate")
    public ResponseEntity<RouteCalculateResponse> calculateRoute(@RequestBody RouteCalculateRequest request) {
        try {
            log.info("Calculating route with {} waypoints, includeInstructions={}",
                    request.getWaypoints() != null ? request.getWaypoints().size() : 0,
                    request.getIncludeInstructions());

            RouteCalculateResponse response = routeService.calculateRoute(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Invalid route calculation request", e);
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error calculating route", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
