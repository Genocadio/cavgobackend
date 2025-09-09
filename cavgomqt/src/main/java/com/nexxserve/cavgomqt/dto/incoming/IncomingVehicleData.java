package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming vehicle data from MQTT messages
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingVehicleData {
    private Integer id;
    private Integer companyId;
    private String companyName;
    private Integer capacity;
    private String licensePlate;
    private IncomingDriverData driver;

    public IncomingVehicleData() {}
}
