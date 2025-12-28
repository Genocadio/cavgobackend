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
public class TripCreateRequest {
    private Long id; // Optional trip ID (if not provided, will be auto-generated)
    private String carId;
    private List<Waypoint> waypoints; // Changed from LatLon to Waypoint (supports id and name)

    private String plateNumber; // Added field matching request body

    @Builder.Default
    private Boolean includeInstructions = false;

    @Builder.Default
    private Boolean includeOrigin = false;

    @Builder.Default
    private Boolean isCityTrip = false;
}
