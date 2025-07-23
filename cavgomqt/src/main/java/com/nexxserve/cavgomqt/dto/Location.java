package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// Location.java
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Location {
    private Integer id;
    private Double latitude;
    private Double price;
    private Double longitude;
    private String code;
    private String googlePlaceName;
    private String customName;
    private String placeId;
    private String createdAt;
    private String updatedAt;

    public Location() {}
}

