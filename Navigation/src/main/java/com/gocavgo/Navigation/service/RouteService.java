package com.gocavgo.Navigation.service;

import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.dto.Instruction;
import com.gocavgo.Navigation.model.dto.RouteCalculateRequest;
import com.gocavgo.Navigation.model.dto.RouteCalculateResponse;
import com.gocavgo.Navigation.model.dto.RouteDto;
import com.gocavgo.Navigation.model.dto.Waypoint;
import com.gocavgo.Navigation.routing.OsrmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteService {
    private final OsrmClient osrmClient;

    public RouteCalculateResponse calculateRoute(RouteCalculateRequest request) {
        if (request.getWaypoints() == null || request.getWaypoints().size() < 2) {
            throw new IllegalArgumentException("At least 2 waypoints are required");
        }

        List<double[]> waypointCoords = request.getWaypoints().stream()
                .map(wp -> new double[]{wp.getLatitude(), wp.getLongitude()})
                .collect(Collectors.toList());

        boolean includeInstructions = Boolean.TRUE.equals(request.getIncludeInstructions());

        // Calculate route
        Route route = osrmClient.getRoute(waypointCoords, includeInstructions);

        // Convert to DTO
        RouteDto routeDto = convertRouteToDto(route);

        // Optionally fetch instructions
        Instruction instructions = null;
        if (includeInstructions) {
            instructions = osrmClient.getInstructions(waypointCoords);
        }

        return RouteCalculateResponse.builder()
                .route(routeDto)
                .instructions(instructions)
                .build();
    }

    private RouteDto convertRouteToDto(Route route) {
        List<List<Double>> polylineDto = route.getPolyline().stream()
                .map(point -> {
                    List<Double> pointList = new ArrayList<>();
                    pointList.add(point[0]); // lat
                    pointList.add(point[1]); // lon
                    return pointList;
                })
                .collect(Collectors.toList());

        return RouteDto.builder()
                .polyline(polylineDto)
                .cumulativeDistances(route.getCumulativeDistances())
                .totalDistance(route.getTotalDistance())
                .totalDuration(route.getTotalDuration())
                .legStopIndices(route.getLegStopIndices())
                .legCumulativeDistances(route.getLegCumulativeDistances())
                .legDurations(route.getLegDurations())
                .build();
    }
}
