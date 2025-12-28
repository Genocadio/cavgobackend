package com.gocavgo.Navigation.model;

import com.gocavgo.Navigation.model.enums.WaypointState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaypointProgress {
    // Waypoint index in the route
    private int waypointIndex;
    
    // Optional waypoint ID
    private String waypointId;
    
    // Optional waypoint name
    private String waypointName;
    
    // Latitude
    private double latitude;
    
    // Longitude
    private double longitude;
    
    // Current state
    private WaypointState state;
    
    // Timestamp when waypoint was arrived at (null if not arrived)
    private Instant arrivedAt;
    
    // Remaining distance to waypoint (meters)
    private double remainingDistance;
    
    // Remaining time to waypoint (seconds)
    private double remainingTime;
}

