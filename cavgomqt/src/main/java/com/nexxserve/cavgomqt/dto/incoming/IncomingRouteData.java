package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming route data from MQTT messages
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingRouteData {
    private Integer id;
    private Integer originId;
    private Integer destinationId;
    private IncomingLocationData origin;
    private IncomingLocationData destination;

    public IncomingRouteData() {}
}
