package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteCalculateResponse {
    private RouteDto route;
    private Instruction instructions;
}
