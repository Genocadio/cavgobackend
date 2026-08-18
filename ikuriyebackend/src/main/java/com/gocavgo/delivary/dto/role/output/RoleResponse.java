package com.gocavgo.delivary.dto.role.output;

import java.util.List;

/**
 * A role definition as managed in Nexxauth. Role names flow into user tokens
 * and are mapped back to local {@link com.gocavgo.delivary.enums.user.Role}
 * authorities by {@code NexxauthRoles}; permissions are resolved server-side by
 * Nexxauth and never shipped in tokens.
 */
public record RoleResponse(
        Long id,
        String name,
        List<String> permissions,
        boolean isDefault
) {
}
