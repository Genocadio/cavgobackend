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
public class Instruction {
    // List of navigation instructions from OSRM
    private List<InstructionStep> steps;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstructionStep {
        private double distance; // meters
        private double duration; // seconds
        private String instruction; // text instruction
        private String maneuver; // maneuver type
        private List<Double> location; // [lon, lat]
    }
}

