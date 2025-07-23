package com.nexxserve.cavgomain.dto.request;

import lombok.Data;

@Data
public class TokenRefreshRequestDto {
    private String refreshToken;
}