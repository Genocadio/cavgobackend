package com.nexxserve.cavgomain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for a staff user requesting access to a company.
 * Only the company code is required — user identity comes from the JWT.
 */
@Data
public class CompanyAccessRequestCreateDto {

    @NotBlank(message = "Company code is required")
    private String companyCode;
}