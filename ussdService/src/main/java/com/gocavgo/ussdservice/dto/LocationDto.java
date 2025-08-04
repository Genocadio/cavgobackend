package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class LocationDto {
    private String id;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal price;
    private String code;

    @JsonProperty("google_place_name")
    private String googlePlaceName;

    @JsonProperty("custom_name")
    private String customName;

    @JsonProperty("place_id")
    private String placeId;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
}