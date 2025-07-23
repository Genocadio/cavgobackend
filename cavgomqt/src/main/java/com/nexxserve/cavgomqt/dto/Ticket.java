package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// Ticket.java
@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Ticket {
    // Getters and Setters
    private String id;
    private String bookingId;
    private String ticketNumber;
    private String qrCode;
    private Boolean isUsed;
    private String createdAt;
    private String updatedAt;
    private String pickupLocationName;
    private String dropoffLocationName;
    private String carPlate;
    private String carCompany;
    private String pickupTime;


}
