package com.gocavgo.delivary.dto.role.input;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Input for {@code updateRole} — updates a Nexxauth role definition. Null
 * fields keep their current value; pass an empty {@code permissions} list to
 * clear all permissions.
 */
public record UpdateRoleInput(
        @NotNull Long roleId,
        String name,
        List<String> permissions,
        Boolean isDefault
) {
}
