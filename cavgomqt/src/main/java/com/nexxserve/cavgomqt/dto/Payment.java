package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// Payment.java
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Payment {
    // Getters and Setters
    private String id;
    private String bookingId;
    private Double amount;
    private String paymentMethod;
    private PaymentStatus status;
    private String createdAt;
    private String updatedAt;

}
