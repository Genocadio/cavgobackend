package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Data
public class TripWaypointDto {
    private String id;

    @JsonProperty("trip_id")
    private Long tripId;

    @JsonProperty("location_id")
    private String locationId;

    private Integer order;
    private BigDecimal price;

    @JsonProperty("is_passed")
    private Boolean isPassed;

    @JsonProperty("is_next")
    private Boolean isNext;

    @JsonProperty("passed_timestamp")
    private Long passedTimestamp;

    @JsonProperty("remaining_time")
    private Long remainingTime;

    @JsonProperty("remaining_distance")
    private Long remainingDistance;

    @JsonProperty("is_custom")
    private Boolean isCustom;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    private LocationDto location;
}
