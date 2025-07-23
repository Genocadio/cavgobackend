package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

// Booking.java
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Booking {
    // Getters and Setters
    private String id;
    private Integer tripId;
    private String userId;
    private String userEmail;
    private String userPhone;
    private String userName;
    private String pickupLocationId;
    private String dropoffLocationId;
    private Integer numberOfTickets;
    private Double totalAmount;
    private BookingStatus status;
    private String bookingReference;
    private String createdAt;
    private String updatedAt;
    private List<Ticket> tickets;
    private Payment payment;


}
