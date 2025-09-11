package com.nexxserve.cavgomqt.dto.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Payment {
    @JsonProperty("id") public String id;
    @JsonProperty("booking_id") public String bookingId;
    @JsonProperty("amount") public double amount;
    @JsonProperty("payment_method") public PaymentMethod paymentMethod;
    @JsonProperty("status") public PaymentStatus status;
    @JsonProperty("transaction_id") public String transactionId;
    @JsonProperty("payment_data") public String paymentData;
    @JsonProperty("created_at") public long createdAt;
    @JsonProperty("updated_at") public long updatedAt;

    public Payment() {}
}


