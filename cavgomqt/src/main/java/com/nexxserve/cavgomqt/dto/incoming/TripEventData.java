package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO for incoming trip event data from MQTT messages
 * Matches the structure sent from the Kotlin client
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TripEventData {
    private Integer id;
    private Integer routeId;
    private Integer vehicleId;
    private IncomingVehicleData vehicle;
    private String status;
    private Long departureTime;
    private Long completionTime;
    private String connectionMode;
    private String notes;
    private Integer seats;
    private Long remainingTimeToDestination;
    private Double remainingDistanceToDestination;
    private Boolean isReversed;
    private Double currentSpeed;
    private Double currentLatitude;
    private Double currentLongitude;
    private Boolean hasCustomWaypoints;
    private String createdAt;
    private String updatedAt;
    private IncomingRouteData route;
    private List<IncomingWaypointData> waypoints;

    public TripEventData() {}
}
