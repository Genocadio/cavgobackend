package com.nexxserve.cavgomain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VehicleLoginRequestDto {
    @NotBlank(message = "Company code is required")
    private String companyCode;

    @NotBlank(message = "License plate is required")
    private String licensePlate;

    @NotBlank(message = "Password is required")
    private String password;

    @NotBlank(message = "Public key is required")
    private String pubKey;
}


