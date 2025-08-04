package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class VehicleDto {
    private Long id;

    @JsonProperty("company_id")
    private Long companyId;

    @JsonProperty("company_name")
    private String companyName;

    private Integer capacity;

    @JsonProperty("license_plate")
    private String licensePlate;

    private DriverDto driver;
}
