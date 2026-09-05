package com.gocavgo.Navigation.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.exception.OsrmUnavailableException;
import com.gocavgo.Navigation.model.Route;
import com.gocavgo.Navigation.model.dto.Instruction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OsrmClient {
    private final OsrmConfig osrmConfig;
    private final ObjectMapper objectMapper;
    
    private RestClient getRestClient() {
        return RestClient.builder()
                .baseUrl(osrmConfig.getUrl())
                .defaultHeader("Accept", "application/json")
                // Don't request compression - OSRM may not handle it correctly
                .defaultHeader("Accept-Encoding", "identity")
                .build();
    }
    
    public Route getRoute(List<double[]> waypoints, boolean includeInstructions) {
        List<Route> routes = getRoutes(waypoints, includeInstructions, 0);
        return routes.get(0);
    }

    public List<Route> getRoutes(List<double[]> waypoints, boolean includeInstructions, int alternatives) {
        if (waypoints == null || waypoints.isEmpty()) {
            throw new IllegalArgumentException("Waypoints cannot be empty");
        }
        if (alternatives < 0) {
            throw new IllegalArgumentException("Alternatives cannot be negative");
        }
        
        // Build OSRM route URL
        StringBuilder urlBuilder = new StringBuilder("/route/v1/driving/");
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) urlBuilder.append(";");
            double[] point = waypoints.get(i);
            urlBuilder.append(point[1]).append(",").append(point[0]); // lon,lat
        }
        urlBuilder.append("?overview=full&geometries=geojson");
        if (includeInstructions) {
            urlBuilder.append("&steps=true");
        }
        if (alternatives > 0) {
            urlBuilder.append("&alternatives=").append(alternatives);
        }
        
        String url = urlBuilder.toString();
        log.debug("Calling OSRM route (alternatives={}): {}", alternatives, url);
        
        try {
            // Use toEntity to get better control over response handling
            org.springframework.http.ResponseEntity<String> responseEntity = getRestClient().get()
                    .uri(url)
                    .retrieve()
                    .toEntity(String.class);
            
            if (responseEntity.getStatusCode().isError()) {
                throw new RuntimeException("OSRM route request failed with status: " + responseEntity.getStatusCode());
            }
            
            String responseBody = responseEntity.getBody();
            if (responseBody == null) {
                throw new RuntimeException("OSRM route request returned null");
            }
            
            JsonNode response = objectMapper.readTree(responseBody);
            
            if (response == null || !response.has("code") || !response.get("code").asText().equals("Ok")) {
                throw new RuntimeException("OSRM route request failed: " + response);
            }
            
            JsonNode routesNode = response.get("routes");
            JsonNode waypointsNode = response.get("waypoints");
            List<Route> routes = new ArrayList<>();
            int routeCount = Math.min(routesNode.size(), alternatives + 1);
            for (int i = 0; i < routeCount; i++) {
                routes.add(parseRoute(routesNode.get(i), waypointsNode, waypoints.size()));
            }
            return routes;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("OSRM API is not reachable at: {}. Please check if OSRM server is running.", osrmConfig.getUrl());
            throw new OsrmUnavailableException(
                    "Routing service (OSRM) is temporarily unavailable. Please try again later.", e);
        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            log.error("OSRM API returned HTTP error. Status: {}, URL: {}", e.getStatusCode(), url);
            if (e instanceof org.springframework.web.client.HttpServerErrorException) {
                throw new OsrmUnavailableException(
                        "Routing service (OSRM) is temporarily unavailable (HTTP " + e.getStatusCode().value() + ").", e);
            }
            throw new RuntimeException("OSRM API error: " + e.getStatusCode() + " - " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Error calling OSRM route API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get route from OSRM: " + e.getMessage(), e);
        }
    }
    
    public Instruction getInstructions(List<double[]> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            return null;
        }
        
        StringBuilder urlBuilder = new StringBuilder("/route/v1/driving/");
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) urlBuilder.append(";");
            double[] point = waypoints.get(i);
            urlBuilder.append(point[1]).append(",").append(point[0]); // lon,lat
        }
        urlBuilder.append("?overview=false&steps=true&geometries=geojson");
        
        String url = urlBuilder.toString();
        log.debug("Calling OSRM instructions: {}", url);
        
        try {
            // Use exchange to get raw response and handle it properly
            org.springframework.http.ResponseEntity<String> responseEntity = getRestClient().get()
                    .uri(url)
                    .retrieve()
                    .toEntity(String.class);
            
            if (responseEntity.getStatusCode().isError()) {
                log.warn("OSRM instructions request failed with status: {}", responseEntity.getStatusCode());
                return null;
            }
            
            String responseBody = responseEntity.getBody();
            if (responseBody == null) {
                return null;
            }
            
            JsonNode response = objectMapper.readTree(responseBody);
            
            if (response == null || !response.has("code") || !response.get("code").asText().equals("Ok")) {
                return null;
            }
            
            return parseInstructions(response);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("OSRM API is not reachable at: {}. Cannot fetch instructions.", osrmConfig.getUrl());
            return null;
        } catch (org.springframework.web.client.HttpClientErrorException | org.springframework.web.client.HttpServerErrorException e) {
            log.warn("OSRM API returned HTTP error when fetching instructions. Status: {}", e.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("Error calling OSRM instructions API: {}", e.getMessage(), e);
            return null;
        }
    }
    
    private Route parseRoute(JsonNode routeNode, JsonNode waypointsNode, int waypointCount) {
        JsonNode geometry = routeNode.get("geometry");
        JsonNode coordinates = geometry.get("coordinates");
        
        List<double[]> polyline = new ArrayList<>();
        List<Double> cumulativeDistances = new ArrayList<>();
        
        double totalDistance = routeNode.get("distance").asDouble();
        double totalDuration = routeNode.get("duration").asDouble();
        
        // Parse polyline coordinates
        double cumulativeDist = 0.0;
        cumulativeDistances.add(0.0);
        
        double[] prevPoint = null;
        for (JsonNode coord : coordinates) {
            double lon = coord.get(0).asDouble();
            double lat = coord.get(1).asDouble();
            double[] point = new double[]{lat, lon};
            polyline.add(point);
            
            if (prevPoint != null) {
                cumulativeDist += haversineDistance(prevPoint[0], prevPoint[1], lat, lon);
            }
            cumulativeDistances.add(cumulativeDist);
            prevPoint = point;
        }
        
        // Parse legs and waypoints for accurate waypoint indices
        JsonNode legs = routeNode.get("legs");
        List<Integer> legStopIndices = new ArrayList<>();
        List<Double> legCumulativeDistances = new ArrayList<>();
        List<Double> legDurations = new ArrayList<>();
        
        // Start point is always at index 0
        legStopIndices.add(0);
        legCumulativeDistances.add(0.0);
        
        // Find waypoint locations in polyline
        if (waypointsNode != null && waypointsNode.isArray()) {
            double cumulativeLegDistance = 0.0;
            
            for (int i = 0; i < waypointsNode.size(); i++) {
                JsonNode waypoint = waypointsNode.get(i);
                JsonNode location = waypoint.get("location");
                if (location != null && location.isArray()) {
                    double waypointLon = location.get(0).asDouble();
                    double waypointLat = location.get(1).asDouble();
                    
                    // Find closest point in polyline to this waypoint
                    int closestIndex = findClosestPolylineIndex(polyline, waypointLat, waypointLon);
                    legStopIndices.add(closestIndex);
                    legCumulativeDistances.add(cumulativeDistances.get(closestIndex));
                }
            }
            
            // Add leg durations
            for (int i = 0; i < legs.size(); i++) {
                JsonNode leg = legs.get(i);
                double legDuration = leg.get("duration").asDouble();
                legDurations.add(legDuration);
            }
        } else {
            // Fallback: approximate waypoint indices from legs
            double cumulativeLegDistance = 0.0;
            for (int i = 0; i < legs.size(); i++) {
                JsonNode leg = legs.get(i);
                double legDistance = leg.get("distance").asDouble();
                double legDuration = leg.get("duration").asDouble();
                
                cumulativeLegDistance += legDistance;
                
                // Find index in polyline closest to cumulative distance
                int closestIndex = findIndexByDistance(cumulativeDistances, cumulativeLegDistance);
                legStopIndices.add(closestIndex);
                legCumulativeDistances.add(cumulativeDistances.get(closestIndex));
                legDurations.add(legDuration);
            }
        }
        
        return Route.builder()
                .polyline(polyline)
                .cumulativeDistances(cumulativeDistances)
                .totalDistance(totalDistance)
                .totalDuration(totalDuration)
                .legStopIndices(legStopIndices)
                .legCumulativeDistances(legCumulativeDistances)
                .legDurations(legDurations)
                .build();
    }
    
    private int findClosestPolylineIndex(List<double[]> polyline, double lat, double lon) {
        int closestIndex = 0;
        double minDistance = Double.MAX_VALUE;
        
        for (int i = 0; i < polyline.size(); i++) {
            double[] point = polyline.get(i);
            double dist = haversineDistance(lat, lon, point[0], point[1]);
            if (dist < minDistance) {
                minDistance = dist;
                closestIndex = i;
            }
        }
        
        return closestIndex;
    }
    
    private int findIndexByDistance(List<Double> cumulativeDistances, double targetDistance) {
        for (int i = 0; i < cumulativeDistances.size(); i++) {
            if (cumulativeDistances.get(i) >= targetDistance) {
                return i;
            }
        }
        return cumulativeDistances.size() - 1;
    }
    
    private Instruction parseInstructions(JsonNode response) {
        JsonNode route = response.get("routes").get(0);
        JsonNode legs = route.get("legs");
        
        List<Instruction.InstructionStep> steps = new ArrayList<>();
        
        for (JsonNode leg : legs) {
            JsonNode stepsNode = leg.get("steps");
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode step : stepsNode) {
                    // Safely extract distance and duration
                    double distance = step.has("distance") ? step.get("distance").asDouble() : 0.0;
                    double duration = step.has("duration") ? step.get("duration").asDouble() : 0.0;
                    
                    // Safely extract instruction from maneuver
                    String instruction = "";
                    String maneuver = "";
                    List<Double> location = null;
                    
                    if (step.has("maneuver")) {
                        JsonNode maneuverNode = step.get("maneuver");
                        if (maneuverNode != null) {
                            if (maneuverNode.has("instruction")) {
                                instruction = maneuverNode.get("instruction").asText();
                            }
                            if (maneuverNode.has("type")) {
                                maneuver = maneuverNode.get("type").asText();
                            }
                            if (maneuverNode.has("location")) {
                                JsonNode locationNode = maneuverNode.get("location");
                                if (locationNode != null && locationNode.isArray() && locationNode.size() >= 2) {
                                    location = new ArrayList<>();
                                    location.add(locationNode.get(0).asDouble()); // lon
                                    location.add(locationNode.get(1).asDouble()); // lat
                                }
                            }
                        }
                    }
                    
                    steps.add(Instruction.InstructionStep.builder()
                            .distance(distance)
                            .duration(duration)
                            .instruction(instruction)
                            .maneuver(maneuver)
                            .location(location)
                            .build());
                }
            }
        }
        
        return Instruction.builder()
                .steps(steps)
                .build();
    }
    
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}

