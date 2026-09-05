package com.gocavgo.Navigation.api;

import com.gocavgo.Navigation.model.dto.RouteCalculateRequest;
import com.gocavgo.Navigation.model.dto.RouteCalculateResponse;
import com.gocavgo.Navigation.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        log.info("Calculating route with {} waypoints, includeInstructions={}, numberOfAlternatives={}",
                request.getWaypoints() != null ? request.getWaypoints().size() : 0,
                request.getIncludeInstructions(),
                request.getNumberOfAlternatives());

        RouteCalculateResponse response = routeService.calculateRoute(request);
        return ResponseEntity.ok(response);
    }
}
