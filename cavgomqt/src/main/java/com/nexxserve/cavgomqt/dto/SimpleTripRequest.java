package com.nexxserve.cavgomqt.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
public class SimpleTripRequest {
    private String tripId;
    private Long vehicleId;
    private String startLocation;
    private String endLocation;
}

