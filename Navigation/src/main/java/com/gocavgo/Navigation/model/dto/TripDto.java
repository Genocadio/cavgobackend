package com.gocavgo.Navigation.model.dto;

import com.gocavgo.Navigation.model.enums.TripStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripDto {
    private Long id;
    private String carId;
    private TripStatus status;
    private List<Waypoint> waypoints; // Changed from LatLon to Waypoint (includes id and name)
    private RouteDto route; // Optional: only included when render=true
    private Instruction instructions; // Optional: only included when render=true and available
    private List<WaypointProgressDto> waypointProgresses;
    private boolean includeOrigin;
    private boolean isCityTrip;
    private Instant createdAt;
    private Instant completedAt;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaypointProgressDto {
        private int waypointIndex;
        private String waypointId; // Optional waypoint ID
        private String waypointName; // Optional waypoint name
        private double latitude;
        private double longitude;
        private String state; // APPROACHING, ARRIVED, DONE
        private Instant arrivedAt;
        private double remainingDistance; // meters
        private double remainingTime; // seconds
    }
}

