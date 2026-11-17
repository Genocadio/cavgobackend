package com.nexxserve.cavgomain.security;

import com.nexxserve.cavgomain.enums.CompanyUserRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NexxauthRolesTest {

    @Test
    void fromNexxauthName_mapsValidNames() {
        assertEquals(CompanyUserRole.ADMIN, NexxauthRoles.fromNexxauthName("admin"));
        assertEquals(CompanyUserRole.DRIVER, NexxauthRoles.fromNexxauthName("DRIVER"));
        assertEquals(CompanyUserRole.FLEET_MANAGER, NexxauthRoles.fromNexxauthName("fleet_manager"));
        assertEquals(CompanyUserRole.SUPERVISOR, NexxauthRoles.fromNexxauthName("Supervisor"));
    }

    @Test
    void fromNexxauthName_returnsNullForUnknown() {
        assertNull(NexxauthRoles.fromNexxauthName("unknown_role"));
        assertNull(NexxauthRoles.fromNexxauthName(""));
        assertNull(NexxauthRoles.fromNexxauthName(null));
    }

    @Test
    void fromNexxauthNames_filtersUnknowns() {
        var result = NexxauthRoles.fromNexxauthNames(List.of("admin", "unknown", "driver"));
        assertEquals(2, result.size());
        assertTrue(result.contains(CompanyUserRole.ADMIN));
        assertTrue(result.contains(CompanyUserRole.DRIVER));
    }

    @Test
    void fromNexxauthNames_handlesNull() {
        assertTrue(NexxauthRoles.fromNexxauthNames(null).isEmpty());
    }

    @Test
    void toNexxauthName_roundTrips() {
        for (CompanyUserRole role : CompanyUserRole.values()) {
            String nexxauthName = NexxauthRoles.toNexxauthName(role);
            assertNotNull(nexxauthName);
            assertEquals(role, NexxauthRoles.fromNexxauthName(nexxauthName));
        }
    }

    @Test
    void primaryRole_picksHighestPrecedence() {
        var authorities = List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_DRIVER"),
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")
        );
        assertEquals(CompanyUserRole.ADMIN, NexxauthRoles.primaryRole(authorities));
    }

    @Test
    void primaryRole_returnsNullWithoutRecognisedRole() {
        // No recognised role must never imply DRIVER — that would grant a role
        // the user was never approved for.
        assertNull(NexxauthRoles.primaryRole(null));
        assertNull(NexxauthRoles.primaryRole(List.of()));
        assertNull(NexxauthRoles.primaryRole(List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }
}
