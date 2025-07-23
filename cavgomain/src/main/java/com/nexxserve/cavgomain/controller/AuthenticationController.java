package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.request.LoginRequestDto;
import com.nexxserve.cavgomain.dto.request.TokenRefreshRequestDto;
import com.nexxserve.cavgomain.dto.response.AuthResponseDto;
import com.nexxserve.cavgomain.service.AuthenticationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/main/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid  @RequestBody LoginRequestDto loginRequest) {
        return ResponseEntity.ok(authenticationService.login(loginRequest));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponseDto> refreshToken(@RequestBody TokenRefreshRequestDto refreshRequest) {
        return ResponseEntity.ok(authenticationService.refreshToken(refreshRequest));
    }

}