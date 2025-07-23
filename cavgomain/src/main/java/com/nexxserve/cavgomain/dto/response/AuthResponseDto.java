package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.enums.CompanyUserRole;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponseDto {
    private String accessToken;
    private String refreshToken;
    private Long userId;
    private String username;
    private String email;
    private String phone;
    private String userType;
    private boolean isCompanyUser;
    private Long companyId;
    private CompanyUserRole companyUserRole;
}