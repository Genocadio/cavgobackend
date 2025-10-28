package com.gocavgo.ridehail.location;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "drivers")
public class Driver {
    @Id
    private Long userId;

    @Column(name = "plate_number", nullable = false, length = 32)
    private String plateNumber;

    @Column(name = "is_available", nullable = false)
    private boolean isAvailable = true;

    @Column(name = "current_location", columnDefinition = "geography(Point,4326)")
    private Point currentLocation;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
    public boolean isAvailable() { return isAvailable; }
    public void setAvailable(boolean available) { isAvailable = available; }
    public Point getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(Point currentLocation) { this.currentLocation = currentLocation; }
}


