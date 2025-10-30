package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for vehicle location update messages sent to RabbitMQ
 * Queue: vehicle.location.updates
 * 
 * Required fields:
 * - status: "ONLINE", "OFFLINE", or "READY"
 * - car_id: Vehicle identifier
 * - timestamp: Unix timestamp in milliseconds
 * 
 * Optional fields (can be null):
 * - current_latitude: GPS latitude
 * - current_longitude: GPS longitude
 * - current_speed: Speed in km/h
 * - accuracy: GPS accuracy in meters
 * - bearing: Direction in degrees (0-360)
 * 
 * Status meanings:
 * - ONLINE: Vehicle is active and operational
 * - READY: Vehicle is available and ready for trip assignment
 * - OFFLINE: Vehicle is not available
 * 
 * Note: Location data should be saved when status is "ONLINE" or "READY" and coordinates are not null.
 */
public class VehicleLocationUpdateMessage {
    
    @JsonProperty("status")
    private String status; // "ONLINE", "OFFLINE", or "READY"
    
    @JsonProperty("car_id")
    private String carId;
    
    @JsonProperty("timestamp")
    private Long timestamp;
    
    @JsonProperty("current_latitude")
    private Double currentLatitude;
    
    @JsonProperty("current_longitude")
    private Double currentLongitude;
    
    @JsonProperty("current_speed")
    private Double currentSpeed;
    
    @JsonProperty("accuracy")
    private Double accuracy;
    
    @JsonProperty("bearing")
    private Double bearing;

    // Getters and setters
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Double getCurrentLatitude() {
        return currentLatitude;
    }

    public void setCurrentLatitude(Double currentLatitude) {
        this.currentLatitude = currentLatitude;
    }

    public Double getCurrentLongitude() {
        return currentLongitude;
    }

    public void setCurrentLongitude(Double currentLongitude) {
        this.currentLongitude = currentLongitude;
    }

    public Double getCurrentSpeed() {
        return currentSpeed;
    }

    public void setCurrentSpeed(Double currentSpeed) {
        this.currentSpeed = currentSpeed;
    }

    public Double getAccuracy() {
        return accuracy;
    }

    public void setAccuracy(Double accuracy) {
        this.accuracy = accuracy;
    }

    public Double getBearing() {
        return bearing;
    }

    public void setBearing(Double bearing) {
        this.bearing = bearing;
    }

    @Override
    public String toString() {
        return "VehicleLocationUpdateMessage{" +
                "status='" + status + '\'' +
                ", carId='" + carId + '\'' +
                ", timestamp=" + timestamp +
                ", currentLatitude=" + currentLatitude +
                ", currentLongitude=" + currentLongitude +
                ", currentSpeed=" + currentSpeed +
                ", accuracy=" + accuracy +
                ", bearing=" + bearing +
                '}';
    }
}

