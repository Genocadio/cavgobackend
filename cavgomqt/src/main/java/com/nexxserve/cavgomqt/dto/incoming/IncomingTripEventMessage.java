package com.nexxserve.cavgomqt.dto.incoming;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO for incoming trip event messages from MQTT
 * This matches the structure sent from the Kotlin client
 */
@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class IncomingTripEventMessage {
    private String event;
    private TripEventData data;

    public IncomingTripEventMessage() {}
}
