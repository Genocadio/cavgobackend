package com.nexxserve.cavgomqt.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingBundle {
    @JsonProperty("trip_id")
    public String tripId;

    @JsonProperty("booking")
    public TripBooking booking;

    @JsonProperty("payment")
    public Payment payment;

    @JsonProperty("tickets")
    public List<Ticket> tickets;

    public BookingBundle() {}
}


