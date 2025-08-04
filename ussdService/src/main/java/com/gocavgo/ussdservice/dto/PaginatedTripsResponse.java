package com.gocavgo.ussdservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class PaginatedTripsResponse {
    private List<TripDto> trips;
    private Long total;
    private Integer limit;
    private Integer offset;

    @JsonProperty("sse_uuid")
    private String sseUuid;
}