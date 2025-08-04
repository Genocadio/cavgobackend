package com.gocavgo.ussdservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TripFilters {
    private String origin;
    private String destination;

    @Builder.Default
    private Integer limit = 20;

    @Builder.Default
    private Integer offset = 0;
}