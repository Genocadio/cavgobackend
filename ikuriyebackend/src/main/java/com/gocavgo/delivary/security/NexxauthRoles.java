package com.gocavgo.delivary.security;

import com.gocavgo.delivary.enums.user.Role;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps between the local {@link Role} enum and the role names provisioned in
 * Nexxauth. Nexxauth is the source of truth for authentication and roles — the
 * org-access JWT carries role *names* (e.g. {@code ["admin", "driver"]}) and the
 * backend maps those names back to the local enum for {@code @PreAuthorize}
 * checks and business logic.
 *
 * <p>The contract: the Nexxauth organisation must provision roles named exactly
 * like the local enum names lower-cased ({@code super_admin}, {@code admin},
 * {@code customer}, {@code worker}, {@code driver}). Any unknown role name from
 * the token is ignored for authorization (it grants nothing locally).
 */
public final class NexxauthRoles {

    private NexxauthRoles() {
    }

    /** Local role -> Nexxauth role name (kept in sync in both directions). */
    private static final Map<Role, String> TO_NEXXAUTH = new EnumMap<>(Role.class);

    static {
        for (Role role : Role.values()) {
            TO_NEXXAUTH.put(role, role.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Higher value = more privileged (used to pick the primary role). */
    private static final Map<Role, Integer> PRECEDENCE = new EnumMap<>(Role.class);

    static {
        PRECEDENCE.put(Role.CUSTOMER, 1);
        PRECEDENCE.put(Role.DRIVER, 2);
        PRECEDENCE.put(Role.WORKER, 3);
        PRECEDENCE.put(Role.ADMIN, 4);
        PRECEDENCE.put(Role.SUPER_ADMIN, 5);
    }

    public static String toNexxauthName(Role role) {
        return TO_NEXXAUTH.get(role);
    }

    /**
     * Maps a Nexxauth role name to the local enum, case-insensitively.
     * Returns {@code null} for unknown names so callers can ignore them.
     */
    public static Role fromNexxauthName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Role.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Maps a list of Nexxauth role names to local roles, skipping unknowns. */
    public static List<Role> fromNexxauthNames(List<String> names) {
        if (names == null) return List.of();
        return names.stream()
                .map(NexxauthRoles::fromNexxauthName)
                .filter(r -> r != null)
                .toList();
    }

    /**
     * Picks the most privileged role from a set of authorities / roles.
     * Used to collapse multiple token roles into the single local {@code role}
     * column (the local schema stores one role per user).
     */
    public static Role primaryRole(Collection<? extends GrantedAuthority> authorities) {
        Role primary = Role.CUSTOMER;
        if (authorities == null) return primary;
        for (GrantedAuthority authority : authorities) {
            Role candidate = fromAuthority(authority);
            if (candidate != null && PRECEDENCE.getOrDefault(candidate, 0) > PRECEDENCE.getOrDefault(primary, 0)) {
                primary = candidate;
            }
        }
        return primary;
    }

    public static Role fromAuthority(GrantedAuthority authority) {
        if (authority == null) return null;
        String name = authority.getAuthority();
        if (name == null || !name.startsWith("ROLE_")) return null;
        return fromNexxauthName(name.substring("ROLE_".length()));
    }
}
