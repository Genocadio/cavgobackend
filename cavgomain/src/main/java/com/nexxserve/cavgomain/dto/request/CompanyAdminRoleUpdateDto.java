package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.enums.CompanyUserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompanyAdminRoleUpdateDto {
    @NotNull
    private CompanyUserRole role;
}
