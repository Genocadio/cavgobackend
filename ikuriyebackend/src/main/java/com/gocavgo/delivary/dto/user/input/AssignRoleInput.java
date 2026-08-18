package com.gocavgo.delivary.dto.user.input;

import com.gocavgo.delivary.enums.user.Role;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Assigns a role to an existing user. Users are registered directly against
 * Nexxauth by the apps (Android/web) — the backend never provisions them, so
 * this input carries no identity fields. The target user must exist in
 * Nexxauth; {@code officeId} is required when the role is {@code WORKER}.
 */
public record AssignRoleInput(
        @NotNull Long userId,
        @NotNull Role role,
        UUID officeId
) {
}
