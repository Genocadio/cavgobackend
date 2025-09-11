package com.nexxserve.cavgomqt.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Ticket {
    @JsonProperty("id") public String id;
    @JsonProperty("booking_id") public String bookingId;
    @JsonProperty("ticket_number") public String ticketNumber;
    @JsonProperty("qr_code") public String qrCode;
    @JsonProperty("is_used") public boolean isUsed;
    @JsonProperty("used_at") public Long usedAt;
    @JsonProperty("validated_by") public String validatedBy;
    @JsonProperty("created_at") public long createdAt;
    @JsonProperty("updated_at") public long updatedAt;
    @JsonProperty("pickup_location_name") public String pickupLocationName;
    @JsonProperty("dropoff_location_name") public String dropoffLocationName;
    @JsonProperty("car_plate") public String carPlate;
    @JsonProperty("car_company") public String carCompany;
    @JsonProperty("pickup_time") public long pickupTime;

    public Ticket() {}
}


