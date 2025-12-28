package com.nexxserve.cavgomqt.dto.naviga;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * DTO for Navigation API trip response from RabbitMQ fanout exchange
 * This matches the TripResponse structure from the Navigation API
 */
@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class NavigationTripResponse {
    private NavigationTripDto trip;
    private NavigationCurrentLocation currentLocation;
    private Object instructions; // Can be null or Instruction object

    @Getter
    @Setter
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NavigationTripDto {
        private Long id;
        private String carId;
        private String status; // CREATED, ACTIVE, COMPLETED, CANCELLED
        private List<NavigationWaypoint> waypoints;
        private List<NavigationWaypointProgress> waypointProgresses;
        private Boolean includeOrigin;
        private Boolean isCityTrip;
        private String createdAt; // ISO 8601
        private String completedAt; // ISO 8601, nullable
    }

    @Getter
    @Setter
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NavigationWaypoint {
        private String id; // Can be null
        private String name; // Can be null
        private Double latitude;
        private Double longitude;
    }

    @Getter
    @Setter
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NavigationWaypointProgress {
        private Integer waypointIndex;
        private String waypointId; // Can be null
        private String waypointName; // Can be null
        private Double latitude;
        private Double longitude;
        private String state; // APPROACHING, ARRIVED, DONE
        private String arrivedAt; // ISO 8601, nullable
        private Double remainingDistance;
        private Double remainingTime;
    }

    @Getter
    @Setter
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class NavigationCurrentLocation {
        private String carId;
        private Double latitude;
        private Double longitude;
        private Double speed;
        private Double heading; // Can be null
        private String timestamp; // ISO 8601
    }
}
