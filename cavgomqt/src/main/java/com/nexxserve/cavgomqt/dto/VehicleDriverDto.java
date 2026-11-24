package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * Current active driver of a vehicle, as returned by cavgomain's internal
 * vehicle endpoint. Used to detect trips whose embedded driver snapshot is
 * stale after a reassignment.
 */
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleDriverDto {
    private Integer id;
    private String name;
    private String phone;
}
