package com.nexxserve.cavgomain.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CompanyAdminAccessRequestDto {

    @NotBlank(message = "Company code is required")
    private String companyCode;
}
