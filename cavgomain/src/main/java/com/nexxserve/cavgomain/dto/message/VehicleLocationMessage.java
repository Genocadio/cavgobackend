package com.nexxserve.cavgomain.dto.message;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleLocationMessage {
    private String status; // "ONLINE", "READY", or "OFFLINE"
    
    @JsonProperty("car_id")
    private String carId; // Vehicle ID as string
    
    private Long timestamp;
    
    @JsonProperty("current_latitude")
    private Double currentLatitude;
    
    @JsonProperty("current_longitude")
    private Double currentLongitude;
    
    @JsonProperty("current_speed")
    private Double currentSpeed;
    
    // Optional fields that might not be in the message
    private Double accuracy; // can be null
    private Double bearing; // can be null
}

