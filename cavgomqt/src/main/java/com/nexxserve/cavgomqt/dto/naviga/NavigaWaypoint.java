package com.nexxserve.cavgomqt.dto.naviga;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for Naviga API waypoint structure
 */
@Getter
@Setter
public class NavigaWaypoint {
    private Double latitude;
    private Double longitude;
    private String name;

    public NavigaWaypoint() {}

    public NavigaWaypoint(Double latitude, Double longitude, String name) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.name = name;
    }
}



