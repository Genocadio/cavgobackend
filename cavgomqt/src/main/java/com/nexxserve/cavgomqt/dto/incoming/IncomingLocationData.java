package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming location data from MQTT messages
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingLocationData {
    private Integer id;
    private Double latitude;
    private Double longitude;
    private String code;
    private String googlePlaceName;
    private String customName;
    private String placeId;
    private String createdAt;
    private String updatedAt;

    public IncomingLocationData() {}
}
