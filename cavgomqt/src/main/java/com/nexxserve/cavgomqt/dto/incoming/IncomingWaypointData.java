package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming waypoint data from MQTT messages
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingWaypointData {
    private Integer id;
    private Integer tripId;
    private Integer locationId;
    private Integer order;
    private Double price;
    private Boolean isPassed;
    private Boolean isNext;
    private Long passedTimestamp;
    private Long remainingTime;
    private Double remainingDistance;
    private Boolean isCustom;
    private String createdAt;
    private String updatedAt;
    private IncomingLocationData location;

    public IncomingWaypointData() {}
}
