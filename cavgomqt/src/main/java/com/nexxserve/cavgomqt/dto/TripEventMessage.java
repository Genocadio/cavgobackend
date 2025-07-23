// src/main/java/com/nexxserve/cavgomqt/dto/TripEventMessage.java
package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TripEventMessage {
    private String event;
    private Trip data;
}