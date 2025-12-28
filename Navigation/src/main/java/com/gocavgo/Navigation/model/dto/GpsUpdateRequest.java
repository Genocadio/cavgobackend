package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpsUpdateRequest {
    private String carId;
    private double latitude;
    private double longitude;
    private double speed; // m/s
    private Double heading; // degrees, optional
    private Double accuracy; // meters, optional
    private Instant timestamp;
}

