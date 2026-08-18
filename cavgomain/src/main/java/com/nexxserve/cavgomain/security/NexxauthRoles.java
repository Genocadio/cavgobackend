package com.nexxserve.cavgomain.security;

import com.nexxserve.cavgomain.enums.CompanyUserRole;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps between the local {@link CompanyUserRole} enum and the role names
 * provisioned in Nexxauth. Nexxauth is the source of truth for authentication
 * and global roles — the org-access JWT carries role names and the backend
 * maps those names back to the local enum.
 *
 * <p>The Nexxauth organisation must provision roles named exactly like the local
 * enum names lower-cased ({@code admin}, {@code driver}, {@code fleet_manager},
 * {@code supervisor}). Any unknown role name from the token is ignored.
 */
public final class NexxauthRoles {

    private NexxauthRoles() {
    }

    private static final Map<CompanyUserRole, String> TO_NEXXAUTH = new EnumMap<>(CompanyUserRole.class);

    static {
        for (CompanyUserRole role : CompanyUserRole.values()) {
            TO_NEXXAUTH.put(role, role.name().toLowerCase(Locale.ROOT));
        }
    }

    private static final Map<CompanyUserRole, Integer> PRECEDENCE = new EnumMap<>(CompanyUserRole.class);

    static {
        PRECEDENCE.put(CompanyUserRole.DRIVER, 1);
        PRECEDENCE.put(CompanyUserRole.FLEET_MANAGER, 2);
        PRECEDENCE.put(CompanyUserRole.SUPERVISOR, 3);
        PRECEDENCE.put(CompanyUserRole.ADMIN, 4);
    }

    public static String toNexxauthName(CompanyUserRole role) {
        return TO_NEXXAUTH.get(role);
    }

    /**
     * Maps a Nexxauth role name to the local enum, case-insensitively.
     * Returns {@code null} for unknown names so callers can ignore them.
     */
    public static CompanyUserRole fromNexxauthName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return CompanyUserRole.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Maps a list of Nexxauth role names to local roles, skipping unknowns.
     */
    public static List<CompanyUserRole> fromNexxauthNames(List<String> names) {
        if (names == null) return List.of();
        return names.stream()
                .map(NexxauthRoles::fromNexxauthName)
                .filter(r -> r != null)
                .toList();
    }

    /**
     * Picks the most privileged role from a set of authorities.
     */
    public static CompanyUserRole primaryRole(Collection<? extends GrantedAuthority> authorities) {
        CompanyUserRole primary = CompanyUserRole.DRIVER;
        if (authorities == null) return primary;
        for (GrantedAuthority authority : authorities) {
            CompanyUserRole candidate = fromAuthority(authority);
            if (candidate != null && PRECEDENCE.getOrDefault(candidate, 0) > PRECEDENCE.getOrDefault(primary, 0)) {
                primary = candidate;
            }
        }
        return primary;
    }

    public static CompanyUserRole fromAuthority(GrantedAuthority authority) {
        if (authority == null) return null;
        String name = authority.getAuthority();
        if (name == null || !name.startsWith("ROLE_")) return null;
        return fromNexxauthName(name.substring("ROLE_".length()));
    }
}
