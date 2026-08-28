package com.gocavgo.delivary.dto.user.input;

import com.gocavgo.delivary.enums.user.Role;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Assigns a role to an existing user. Users are registered directly against
 * Nexxauth by the apps (Android/web) — the backend never provisions them, so
 * this input carries no identity fields. The target user must exist in
 * Nexxauth.
 */
public record AssignRoleInput(
        @NotNull Long userId,
        @NotNull Role role,
        UUID officeId
) {
}
