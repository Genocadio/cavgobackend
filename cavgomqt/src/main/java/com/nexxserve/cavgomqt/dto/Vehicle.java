package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// Vehicle.java
@Setter
@Getter
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Vehicle {
    // Getters and Setters
    private Integer id;
    private Integer companyId;
    private String companyName;
    private Integer capacity;
    private String licensePlate;
    private Driver driver;

    // Constructors
    public Vehicle() {}

}
