package com.nexxserve.cavgomqt.dto;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.Setter;

// Driver.java
@Setter
@Getter
@JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Driver {
    // Getters and Setters
    private String name;
    private String phone;


}
