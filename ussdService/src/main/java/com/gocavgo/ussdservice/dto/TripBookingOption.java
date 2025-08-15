package com.gocavgo.ussdservice.dto;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripBookingOption {
    private Long tripId;
    private String originLocationId;
    private String destinationLocationId;
    private String originName;
    private String destinationName;
    private String price;
    private String departureTime;
    private Integer availableSeats;
    private boolean originIsWaypoint;
    private boolean destinationIsWaypoint;
}