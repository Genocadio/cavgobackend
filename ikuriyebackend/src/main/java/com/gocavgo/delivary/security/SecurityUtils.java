package com.gocavgo.delivary.security;

import com.gocavgo.delivary.enums.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Extracts the current user's role from the SecurityContext — the single source
 * of truth for authorization. The role is derived from the JWT token's
 * {@code roles} claim via {@link NexxauthRoles}.
 *
 * <p>This replaces all former {@code user.getRole()} lookups against the local
 * {@code users} table. Roles are now verified exclusively from valid tokens,
 * and role changes in Nexxauth take effect as soon as the user gets a new token.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * Returns the primary (most privileged) role of the currently authenticated
     * user, derived from their JWT token authorities. Returns {@code null} if no
     * authenticated user exists or the token carries no recognized roles.
     */
    public static Role getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        return NexxauthRoles.primaryRole(auth.getAuthorities());
    }

    /**
     * Returns the Nexxauth user ID of the currently authenticated user, or
     * {@code null} if not authenticated.
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns {@code true} if the current user holds any of the given roles.
     */
    public static boolean hasAnyRole(Role... roles) {
        Role current = getCurrentUserRole();
        if (current == null) return false;
        for (Role r : roles) {
            if (current == r) return true;
        }
        return false;
    }
}
