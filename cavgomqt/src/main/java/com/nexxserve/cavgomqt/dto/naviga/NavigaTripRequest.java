package com.nexxserve.cavgomqt.dto.naviga;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/**
 * DTO for Naviga API trip creation request
 */
@Getter
@Setter
public class NavigaTripRequest {
    private Long id;
    private String carId;
    @com.fasterxml.jackson.annotation.JsonProperty("includeInstructions")
    private boolean includeInstructions = false;

    @com.fasterxml.jackson.annotation.JsonProperty("includeOrigin")
    private boolean includeOrigin = false;

    @com.fasterxml.jackson.annotation.JsonProperty("isCityTrip")
    private boolean isCityTrip = false;
    private List<NavigaWaypoint> waypoints;

    public NavigaTripRequest() {
    }
}
