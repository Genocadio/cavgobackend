package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteDto {
    // Polyline points: [[lat, lon], [lat, lon], ...]
    private List<List<Double>> polyline;
    
    // Cumulative distances from start for each polyline point (meters)
    private List<Double> cumulativeDistances;
    
    // Total route distance (meters)
    private double totalDistance;
    
    // Total route duration (seconds)
    private double totalDuration;
    
    // Indices in polyline where each waypoint/stop is located
    private List<Integer> legStopIndices;
    
    // Cumulative distances at each waypoint/stop
    private List<Double> legCumulativeDistances;
    
    // Leg durations (seconds) between waypoints
    private List<Double> legDurations;
}



