package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentLocation {
    private String carId;
    private double latitude;
    private double longitude;
    private double speed; // m/s
    private Double heading; // degrees, nullable
    private Instant timestamp;
}

