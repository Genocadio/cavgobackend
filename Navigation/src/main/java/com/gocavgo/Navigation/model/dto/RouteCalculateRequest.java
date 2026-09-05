package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteCalculateRequest {
    private List<Waypoint> waypoints;

    @Builder.Default
    private Boolean includeInstructions = false;

    @Builder.Default
    private Integer numberOfAlternatives = 0;
}
