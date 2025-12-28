package com.gocavgo.Navigation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationState {
    // Last polyline index where GPS was snapped
    private int lastSnappedIndex;
    
    // Total distance travelled along route (meters)
    private double distanceTravelled;
    
    // Current leg index (which waypoint we're heading to)
    private int currentLegIndex;
    
    // Last GPS update timestamp
    private Instant lastUpdateTime;
    
    // Average speed (m/s) for ETA calculation
    private double avgSpeed;
    
    // Off-route detection counter
    private int offRouteConsecutiveCount;
    
    // Highest waypoint state reached (as JSON: {"0":"DONE","1":"ARRIVED",...})
    // Stored as JSON string to track waypoint states persistently
    private String waypointStatesJson; // JSON map of waypointIndex -> WaypointState name
    
    // Current location (map-matched)
    private Double currentLatitude;
    private Double currentLongitude;
    private Double currentSpeed; // m/s
    private Double currentHeading; // degrees, nullable
    private Instant currentLocationTimestamp;
    
    // Trip ID this state belongs to (to prevent cross-trip contamination)
    private Long tripId;
}

