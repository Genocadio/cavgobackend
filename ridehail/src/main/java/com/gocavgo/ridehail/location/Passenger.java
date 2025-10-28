package com.gocavgo.ridehail.location;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "passengers")
public class Passenger {
    @Id
    private Long userId;

    @Column(name = "current_location", columnDefinition = "geography(Point,4326)")
    private Point currentLocation;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Point getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(Point currentLocation) { this.currentLocation = currentLocation; }
}


