package com.nexxserve.cavgomqt.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class VehicleDto {
    // Getters and setters
    private Long id;
    private Long companyId;
    private String companyName;
    private String make;
    private String model;
    private int capacity;
    private String licensePlate;
    private String vehicleType;
    private String status;
    private String createdAt;
    private String updatedAt;
    private Object driver;

}
