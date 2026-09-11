package com.nexxserve.cavgomain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for a normal user requesting to become a driver.
 * Only the company code is required — user identity comes from the JWT.
 */
@Data
public class DriverRequestCreateDto {
    @NotBlank(message = "Company code is required")
    private String companyCode;
}
