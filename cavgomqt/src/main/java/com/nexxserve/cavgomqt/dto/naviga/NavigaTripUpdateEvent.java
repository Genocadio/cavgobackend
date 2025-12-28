package com.nexxserve.cavgomqt.dto.naviga;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Event published to RabbitMQ fanout exchange (cavgomqt.trip.updates)
 * whenever a Naviga API call completes successfully.
 * 
 * All events are of type "updates" per the requirement.
 * The trip object reflects the response from Naviga API.
 */
public class NavigaTripUpdateEvent {

    @JsonProperty("eventType")
    private String eventType = "updates"; // Always "updates"

    @JsonProperty("trip")
    private NavigaTripDto trip;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("source")
    private String source; // e.g., "naviga-api" or "naviga-gps-batch"

    /**
     * No-arg constructor for deserialization
     */
    public NavigaTripUpdateEvent() {
        this.timestamp = Instant.now();
        this.source = "naviga-api";
    }

    /**
     * Constructor with trip data
     */
    public NavigaTripUpdateEvent(NavigaTripDto trip, String source) {
        this.trip = trip;
        this.timestamp = Instant.now();
        this.source = source != null ? source : "naviga-api";
        this.eventType = "updates";
    }

    // Getters and setters
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public NavigaTripDto getTrip() {
        return trip;
    }

    public void setTrip(NavigaTripDto trip) {
        this.trip = trip;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public String toString() {
        return "NavigaTripUpdateEvent{" +
                "eventType='" + eventType + '\'' +
                ", trip=" + trip +
                ", timestamp=" + timestamp +
                ", source='" + source + '\'' +
                '}';
    }

    /**
     * Simplified Naviga trip DTO matching the response structure from Naviga API
     */
    public static class NavigaTripDto {

        @JsonProperty("id")
        private Long id;

        @JsonProperty("carId")
        private String carId;

        @JsonProperty("status")
        private String status; // CREATED | ACTIVE | COMPLETED | CANCELLED

        @JsonProperty("createdAt")
        private Instant createdAt;

        @JsonProperty("completedAt")
        private Instant completedAt;

        @JsonProperty("waypointProgresses")
        private java.util.List<WaypointProgressDto> waypointProgresses;

        @JsonProperty("currentLocation")
        private CurrentLocationDto currentLocation;

        public NavigaTripDto() {
        }

        public NavigaTripDto(Long id, String carId, String status) {
            this.id = id;
            this.carId = carId;
            this.status = status;
            this.createdAt = Instant.now();
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getCarId() {
            return carId;
        }

        public void setCarId(String carId) {
            this.carId = carId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(Instant completedAt) {
            this.completedAt = completedAt;
        }

        public java.util.List<WaypointProgressDto> getWaypointProgresses() {
            return waypointProgresses;
        }

        public void setWaypointProgresses(java.util.List<WaypointProgressDto> waypointProgresses) {
            this.waypointProgresses = waypointProgresses;
        }

        public CurrentLocationDto getCurrentLocation() {
            return currentLocation;
        }

        public void setCurrentLocation(CurrentLocationDto currentLocation) {
            this.currentLocation = currentLocation;
        }

        @Override
        public String toString() {
            return "NavigaTripDto{" +
                    "id=" + id +
                    ", carId='" + carId + '\'' +
                    ", status='" + status + '\'' +
                    ", createdAt=" + createdAt +
                    ", completedAt=" + completedAt +
                    ", waypointProgresses=" + (waypointProgresses != null ? waypointProgresses.size() + " waypoints" : "null") +
                    ", currentLocation=" + currentLocation +
                    '}';
        }
    }

    /**
     * Waypoint progress DTO from Naviga API
     */
    public static class WaypointProgressDto {
        @JsonProperty("waypointIndex")
        private Integer waypointIndex;

        @JsonProperty("waypointId")
        private String waypointId;

        @JsonProperty("waypointName")
        private String waypointName;

        @JsonProperty("latitude")
        private Double latitude;

        @JsonProperty("longitude")
        private Double longitude;

        @JsonProperty("state")
        private String state; // APPROACHING | ARRIVED | DONE

        @JsonProperty("arrivedAt")
        private Instant arrivedAt;

        @JsonProperty("remainingDistance")
        private Double remainingDistance;

        @JsonProperty("remainingTime")
        private Double remainingTime;

        public WaypointProgressDto() {
        }

        public Integer getWaypointIndex() {
            return waypointIndex;
        }

        public void setWaypointIndex(Integer waypointIndex) {
            this.waypointIndex = waypointIndex;
        }

        public String getWaypointId() {
            return waypointId;
        }

        public void setWaypointId(String waypointId) {
            this.waypointId = waypointId;
        }

        public String getWaypointName() {
            return waypointName;
        }

        public void setWaypointName(String waypointName) {
            this.waypointName = waypointName;
        }

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }

        public String getState() {
            return state;
        }

        public void setState(String state) {
            this.state = state;
        }

        public Instant getArrivedAt() {
            return arrivedAt;
        }

        public void setArrivedAt(Instant arrivedAt) {
            this.arrivedAt = arrivedAt;
        }

        public Double getRemainingDistance() {
            return remainingDistance;
        }

        public void setRemainingDistance(Double remainingDistance) {
            this.remainingDistance = remainingDistance;
        }

        public Double getRemainingTime() {
            return remainingTime;
        }

        public void setRemainingTime(Double remainingTime) {
            this.remainingTime = remainingTime;
        }

        @Override
        public String toString() {
            return "WaypointProgressDto{" +
                    "waypointIndex=" + waypointIndex +
                    ", state='" + state + '\'' +
                    ", remainingDistance=" + remainingDistance +
                    ", remainingTime=" + remainingTime +
                    '}';
        }
    }

    /**
     * Current location DTO from Naviga API (map-matched/snapped coordinates)
     */
    public static class CurrentLocationDto {
        @JsonProperty("carId")
        private String carId;

        @JsonProperty("latitude")
        private Double latitude;

        @JsonProperty("longitude")
        private Double longitude;

        @JsonProperty("speed")
        private Double speed;

        @JsonProperty("heading")
        private Double heading;

        @JsonProperty("timestamp")
        private Instant timestamp;

        public CurrentLocationDto() {
        }

        public String getCarId() {
            return carId;
        }

        public void setCarId(String carId) {
            this.carId = carId;
        }

        public Double getLatitude() {
            return latitude;
        }

        public void setLatitude(Double latitude) {
            this.latitude = latitude;
        }

        public Double getLongitude() {
            return longitude;
        }

        public void setLongitude(Double longitude) {
            this.longitude = longitude;
        }

        public Double getSpeed() {
            return speed;
        }

        public void setSpeed(Double speed) {
            this.speed = speed;
        }

        public Double getHeading() {
            return heading;
        }

        public void setHeading(Double heading) {
            this.heading = heading;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Instant timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "CurrentLocationDto{" +
                    "carId='" + carId + '\'' +
                    ", latitude=" + latitude +
                    ", longitude=" + longitude +
                    ", speed=" + speed +
                    ", heading=" + heading +
                    ", timestamp=" + timestamp +
                    '}';
        }
    }
}
