package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleDto {
    // Getters and setters
    private Long id;
    private Long companyId;
    private String companyName;
    private String make;
    private String model;
    private int capacity;

    @JsonAlias({"plate", "licensePlate"})
    private String licensePlate;

    private String vehicleType;
    private String status;
    private String createdAt;
    private String updatedAt;
    private Object driver;

}
