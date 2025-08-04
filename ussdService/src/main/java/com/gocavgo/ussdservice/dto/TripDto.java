package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

@Data
public class TripDto {
    private Long id;

    @JsonProperty("route_id")
    private Long routeId;

    @JsonProperty("vehicle_id")
    private Long vehicleId;

    private VehicleDto vehicle;
    private TripStatus status;

    @JsonProperty("departure_time")
    private Long departureTime;

    @JsonProperty("completion_time")
    private Long completionTime;

    @JsonProperty("connection_mode")
    private ConnectionMode connectionMode;

    private String notes;
    private Integer seats;

    @JsonProperty("remaining_time_to_destination")
    private Long remainingTimeToDestination;

    @JsonProperty("remaining_distance_to_destination")
    private Long remainingDistanceToDestination;

    @JsonProperty("is_reversed")
    private Boolean isReversed;

    @JsonProperty("current_speed")
    private BigDecimal currentSpeed;

    @JsonProperty("current_latitude")
    private BigDecimal currentLatitude;

    @JsonProperty("current_longitude")
    private BigDecimal currentLongitude;

    @JsonProperty("has_custom_waypoints")
    private Boolean hasCustomWaypoints;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    private RouteDto route;
    private List<TripWaypointDto> waypoints;

    public enum TripStatus {
        SCHEDULED,
        IN_PROGRESS,
        COMPLETED,
        NOT_COMPLETED
    }

    public enum ConnectionMode {
        ONLINE,
        OFFLINE,
        HYBRID
    }
}