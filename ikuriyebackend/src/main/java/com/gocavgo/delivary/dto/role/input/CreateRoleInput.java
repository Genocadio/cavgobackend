package com.gocavgo.delivary.dto.role.input;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Input for {@code createRole} — creates a role definition in Nexxauth.
 * Use the lower-cased local role name ({@code super_admin}, {@code admin},
 * {@code customer}, {@code worker}, {@code driver}) so the backend's
 * {@code @PreAuthorize} checks recognize it in user tokens.
 */
public record CreateRoleInput(
        @NotBlank String name,
        @NotNull List<String> permissions,
        Boolean isDefault
) {
    public CreateRoleInput {
        if (isDefault == null) isDefault = false;
    }
}
