package com.nexxserve.cavgomqt.dto.naviga;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for Naviga API GPS update request
 */
@Getter
@Setter
public class NavigaGpsUpdateRequest {
    private String carId;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Double heading;
    private String timestamp;
    private Double accuracy;

    public NavigaGpsUpdateRequest() {
    }
}
