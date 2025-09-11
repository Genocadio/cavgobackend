package com.nexxserve.cavgomqt.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TripBooking {
    @JsonProperty("id") public String id;
    @JsonProperty("trip_id") public int tripId;
    @JsonProperty("user_id") public String userId;
    @JsonProperty("user_email") public String userEmail;
    @JsonProperty("user_phone") public String userPhone;
    @JsonProperty("user_name") public String userName;
    @JsonProperty("pickup_location_id") public String pickupLocationId;
    @JsonProperty("dropoff_location_id") public String dropoffLocationId;
    @JsonProperty("number_of_tickets") public int numberOfTickets;
    @JsonProperty("total_amount") public double totalAmount;
    @JsonProperty("status") public BookingStatus status;
    @JsonProperty("booking_reference") public String bookingReference;
    @JsonProperty("created_at") public long createdAt;
    @JsonProperty("updated_at") public long updatedAt;

    public TripBooking() {}
}


