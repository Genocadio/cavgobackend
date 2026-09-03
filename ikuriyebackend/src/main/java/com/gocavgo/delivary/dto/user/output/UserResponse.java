package com.gocavgo.delivary.dto.user.output;

import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;

import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String phone,
        String firstName,
        String lastName,
        String username,
        String avatarUrl,
        Role role,
        UserStatus status,
        String dataHash,
        Instant createdAt,
        Instant updatedAt,
        String driverStatus
) {
}
