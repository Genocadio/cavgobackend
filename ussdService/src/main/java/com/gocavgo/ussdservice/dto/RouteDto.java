package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

@Data
public class RouteDto {
    private Long id;
    private String name;

    @JsonProperty("distance_meters")
    private Long distanceMeters;

    @JsonProperty("estimated_duration_seconds")
    private Long estimatedDurationSeconds;

    @JsonProperty("google_route_id")
    private String googleRouteId;

    @JsonProperty("origin_id")
    private String originId;

    @JsonProperty("destination_id")
    private String destinationId;

    @JsonProperty("route_price")
    private BigDecimal routePrice;

    @JsonProperty("city_route")
    private Boolean cityRoute;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;

    private LocationDto origin;
    private LocationDto destination;
    private List<Object> waypoints;
}