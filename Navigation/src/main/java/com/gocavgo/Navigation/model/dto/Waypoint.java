package com.gocavgo.Navigation.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Waypoint {
    private String id; // Optional waypoint ID
    private String name; // Optional waypoint name
    private double latitude;
    private double longitude;
}



