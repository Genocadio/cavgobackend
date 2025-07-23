package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// Trip.java
@Setter
@Getter
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Trip {
    // Getters and Setters
    private Integer id;
    private Integer routeId;
    private Integer vehicleId;
    private Vehicle vehicle;
    private TripStatus status;
    private Long departureTime;
    private Long completionTime;
    private ConnectionMode connectionMode;
    private String notes;
    private Integer seats;
    private Long remainingTimeToDestination;
    private Long remainingDistanceToDestination;
    private Boolean isReversed;
    private Double currentSpeed;
    private Double currentLatitude;
    private Double currentLongitude;
    private Boolean hasCustomWaypoints;
    private String createdAt;
    private String updatedAt;
    private Route route;
    private List<TripWaypoint> waypoints;

    // Constructors
    public Trip() {}

}
