package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// TripWaypoint.java
@Setter
@Getter
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TripWaypoint {
    // Getters and Setters
    private Integer id;
    private Integer tripId;
    private Integer locationId;
    private Integer order;
    private Double price;
    private Boolean isPassed;
    private Boolean isNext;
    private Long passedTimestamp;
    private Long remainingTime;
    private Long remainingDistance;
    private Boolean isCustom;
    private String createdAt;
    private String updatedAt;
    private Location location;

    // Constructors
    public TripWaypoint() {}

}
