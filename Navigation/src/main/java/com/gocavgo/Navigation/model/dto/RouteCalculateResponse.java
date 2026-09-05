package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteCalculateResponse {
    private RouteDto route;

    @Builder.Default
    private List<RouteDto> alternatives = new ArrayList<>();

    private Instruction instructions;
}
