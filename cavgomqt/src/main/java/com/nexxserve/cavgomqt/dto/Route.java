package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// Route.java
@Getter
@Setter
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Route {
    private Integer id;
    private String name;
    private Long distanceMeters;
    private Long estimatedDurationSeconds;
    private String googleRouteId;
    private Integer originId;
    private Integer destinationId;
    private Double routePrice;
    private Boolean cityRoute;
    private String createdAt;
    private String updatedAt;
    private Location origin;
    private Location destination;
    private List<Object> waypoints;

}
