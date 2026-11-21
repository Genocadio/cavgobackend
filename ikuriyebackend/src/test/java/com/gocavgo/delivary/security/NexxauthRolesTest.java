package com.gocavgo.delivary.security;

import com.gocavgo.delivary.enums.user.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NexxauthRolesTest {

    @Test
    void fleetManagerIsAnAliasForWorker() {
        assertEquals(Role.WORKER, NexxauthRoles.fromNexxauthName("FLEET_MANAGER"));
        assertEquals(Role.WORKER, NexxauthRoles.fromNexxauthName("fleet_manager"));
        assertEquals(Role.WORKER, NexxauthRoles.fromNexxauthName(" Fleet_Manager "));
    }

    @Test
    void knownRolesAndUnknownRolesAreUnaffected() {
        assertEquals(Role.DRIVER, NexxauthRoles.fromNexxauthName("DRIVER"));
        assertEquals(Role.CUSTOMER, NexxauthRoles.fromNexxauthName("customer"));
        assertNull(NexxauthRoles.fromNexxauthName("SUPERVISOR"));
        assertNull(NexxauthRoles.fromNexxauthName(null));
    }
}
