package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for incoming vehicle status/heartbeat messages from MQTT
 * Topic: car/{carId}/heartbeat or car/{carId}/status
 * 
 * All fields except status are optional.
 * - status: Required ("ONLINE", "OFFLINE", or "READY")
 * - car_id: Optional (extracted from topic if missing)
 * - timestamp: Optional (will use current time if missing)
 * - Location fields (latitude, longitude, speed, accuracy, bearing): All optional
 */
public class IncomingVehicleStatusData {
    
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
        return "IncomingVehicleStatusData{" +
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

