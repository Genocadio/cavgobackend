package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming driver data from MQTT messages
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingDriverData {
    private String name;
    private String phone;

    public IncomingDriverData() {}
}
