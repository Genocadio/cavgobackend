package com.nexxserve.cavgomqt.dto.naviga;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Event published to RabbitMQ fanout exchange (cavgomqt.location.updates)
 * whenever a batch of GPS location updates is received from MQTT and decoded.
 * 
 * Published BEFORE sending to Naviga API.
 * All events are of type "updates" per the requirement.
 */
public class NavigaLocationUpdateEvent {

    @JsonProperty("eventType")
    private String eventType = "updates"; // Always "updates"

    @JsonProperty("carId")
    private String carId;

    @JsonProperty("locations")
    private List<NavigaLocationDto> locations;

    @JsonProperty("timestamp")
    private Instant timestamp;

    @JsonProperty("source")
    private String source; // e.g., "location-batch"

    /**
     * No-arg constructor for deserialization
     */
    public NavigaLocationUpdateEvent() {
        this.timestamp = Instant.now();
        this.source = "location-batch";
    }

    /**
     * Constructor with location batch data
     */
    public NavigaLocationUpdateEvent(String carId, List<NavigaLocationDto> locations, String source) {
        this.carId = carId;
        this.locations = locations;
        this.timestamp = Instant.now();
        this.source = source != null ? source : "location-batch";
        this.eventType = "updates";
    }

    // Getters and setters
    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getCarId() {
        return carId;
    }

    public void setCarId(String carId) {
        this.carId = carId;
    }

    public List<NavigaLocationDto> getLocations() {
        return locations;
    }

    public void setLocations(List<NavigaLocationDto> locations) {
        this.locations = locations;
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
        return "NavigaLocationUpdateEvent{" +
                "eventType='" + eventType + '\'' +
                ", carId='" + carId + '\'' +
                ", locations=" + (locations != null ? locations.size() : 0) + " items" +
                ", timestamp=" + timestamp +
                ", source='" + source + '\'' +
                '}';
    }

    /**
     * Individual location DTO matching GPS update structure
     */
    public static class NavigaLocationDto {

        @JsonProperty("carId")
        private String carId;

        @JsonProperty("latitude")
        private Double latitude;

        @JsonProperty("longitude")
        private Double longitude;

        @JsonProperty("speed")
        private Double speed; // m/s

        @JsonProperty("heading")
        private Double heading; // degrees

        @JsonProperty("accuracy")
        private Double accuracy; // meters

        @JsonProperty("timestamp")
        private String timestamp; // ISO 8601

        public NavigaLocationDto() {
        }

        public NavigaLocationDto(String carId, Double latitude, Double longitude, 
                                Double speed, Double heading, Double accuracy, String timestamp) {
            this.carId = carId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speed = speed;
            this.heading = heading;
            this.accuracy = accuracy;
            this.timestamp = timestamp;
        }

        // Getters and setters
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

        public Double getAccuracy() {
            return accuracy;
        }

        public void setAccuracy(Double accuracy) {
            this.accuracy = accuracy;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "NavigaLocationDto{" +
                    "carId='" + carId + '\'' +
                    ", latitude=" + latitude +
                    ", longitude=" + longitude +
                    ", speed=" + speed +
                    ", heading=" + heading +
                    ", accuracy=" + accuracy +
                    ", timestamp='" + timestamp + '\'' +
                    '}';
        }
    }
}
