package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.request.LoginRequestDto;
import com.nexxserve.cavgomain.dto.request.TokenRefreshRequestDto;
import com.nexxserve.cavgomain.dto.response.AuthResponseDto;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.User;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.UserRepository;
import com.nexxserve.cavgomain.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AuthenticationService {

    private final UserRepository userRepository;
    private final CompanyUserRepository companyUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthResponseDto login(LoginRequestDto loginRequest) {
        try {
            // Find user by email or phone
            User user = userRepository.findByEmail(loginRequest.getEmailOrPhone())
                    .orElseGet(() -> userRepository.findByPhone(loginRequest.getEmailOrPhone())
                            .orElseThrow(() -> new BadCredentialsException("Invalid credentials")));

            // Check if user is active
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new IllegalStateException("User account is not active");
            }

            // Verify password
            if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
                throw new BadCredentialsException("Invalid credentials");
            }

            // Generate tokens
            String accessToken = tokenProvider.generateAccessToken(user);
            String refreshToken = tokenProvider.generateRefreshToken(user);

            // Build response with user details
            AuthResponseDto.AuthResponseDtoBuilder responseBuilder = AuthResponseDto.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .userId(user.getId())
                    .userType(user.getClass().getSimpleName());

            // Add company details if applicable
            // Inside login() and refreshToken() methods, update this block:
            if (user instanceof CompanyUser companyUser) {
                responseBuilder
                    .isCompanyUser(true)
                    .companyId(companyUser.getCompany().getId())
                        .companyName(companyUser.getCompany().getCompanyName());
                if (companyUser.getRole() != null) {
                    responseBuilder.companyUserRole(companyUser.getRole());
                }

            } else {
                responseBuilder.isCompanyUser(false);
            }
            responseBuilder.username(user.getFirstName() + " " + user.getLastName());
            responseBuilder.email(user.getEmail());
            responseBuilder.phone(user.getPhone());

            return responseBuilder.build();
        } catch (
            BadCredentialsException e) {
            log.error(e.getMessage());
            throw new BadCredentialsException("Invalid email or password", e);
        } catch (IllegalStateException e) {
            log.error(e.getMessage());
            throw new IllegalStateException("User account is not active", e);
        } catch (Exception e) {
            log.error("An error occurred during login: {}", e.getMessage());
            throw new RuntimeException("An error occurred during login", e);
        }


    }

    public AuthResponseDto refreshToken(TokenRefreshRequestDto refreshRequest) {
        String refreshToken = refreshRequest.getRefreshToken();


        // Validate token
        if (!tokenProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        // Extract user email and find user
        String email = tokenProvider.extractUsername(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Check if user is still active
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalStateException("User account is not active");
        }

        // Generate new access token
        String newAccessToken = tokenProvider.generateAccessToken(user);

        // Build response with user details
        AuthResponseDto.AuthResponseDtoBuilder responseBuilder = AuthResponseDto.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken) // Reuse the same refresh token
                .userId(user.getId())
                .userType(user.getClass().getSimpleName());

        // Add company details if applicable
        if (user instanceof CompanyUser companyUser) {
            responseBuilder
                .isCompanyUser(true)
                .companyId(companyUser.getCompany().getId())
                .companyUserRole(companyUser.getRole());
        } else {
            responseBuilder.isCompanyUser(false);
        }

        return responseBuilder.build();
    }

}