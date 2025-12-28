package com.nexxserve.cavgomqt.dto.naviga;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for Naviga API waypoint structure
 */
@Getter
@Setter
public class NavigaWaypoint {
    private Integer id;
    private Double latitude;
    private Double longitude;
    private String name;

    public NavigaWaypoint() {}

    public NavigaWaypoint(Double latitude, Double longitude, String name) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.name = name;
    }

    public NavigaWaypoint(Integer id, Double latitude, Double longitude, String name) {
        this.id = id;
        this.latitude = latitude;
        this.longitude = longitude;
        this.name = name;
    }
}



