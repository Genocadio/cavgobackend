package com.gocavgo.delivary.config;

import com.gocavgo.delivary.enums.user.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A token role must not become an effective authority until cavgomain confirms
 * an approved company membership with that role. These tests pin the approval
 * gate used by both the HTTP JWT filter and the WebSocket interceptor.
 */
class CavgomainSyncClientApprovalTest {

    private final CavgomainSyncClient enabled = new CavgomainSyncClient("http://cavgomain:6060");
    private final CavgomainSyncClient disabled = new CavgomainSyncClient("");

    private CavgomainSyncClient.CavgomainUser membership(Long companyId, String role) {
        return new CavgomainSyncClient.CavgomainUser(7L, companyId, role, null);
    }

    @Test
    void identityAndAdminRolesAlwaysEffective() {
        assertTrue(enabled.isRoleEffective(Role.CUSTOMER, null, 7L));
        assertTrue(enabled.isRoleEffective(Role.ADMIN, null, 7L));
        assertTrue(enabled.isRoleEffective(Role.SUPER_ADMIN, null, 7L));
    }

    @Test
    void driverRoleRequiresApprovedMembership() {
        assertTrue(enabled.isRoleEffective(Role.DRIVER, membership(1L, "DRIVER"), 7L));
        // Approved as a different role, or no company membership at all.
        assertFalse(enabled.isRoleEffective(Role.DRIVER, membership(1L, "WORKER"), 7L));
        assertFalse(enabled.isRoleEffective(Role.DRIVER, membership(null, "DRIVER"), 7L));
        assertFalse(enabled.isRoleEffective(Role.DRIVER, null, 7L));
    }

    @Test
    void workerRoleRequiresApprovedMembership() {
        assertTrue(enabled.isRoleEffective(Role.WORKER, membership(1L, "WORKER"), 7L));
        assertFalse(enabled.isRoleEffective(Role.WORKER, membership(1L, "DRIVER"), 7L));
        assertFalse(enabled.isRoleEffective(Role.WORKER, membership(null, "WORKER"), 7L));
        assertFalse(enabled.isRoleEffective(Role.WORKER, null, 7L));
    }

    @Test
    void fleetManagerMembershipSatisfiesWorkerOnlyWhenTokenSaysFleetManager() {
        var tokenRoles = java.util.List.of("FLEET_MANAGER");

        assertTrue(enabled.isRoleEffective(Role.WORKER, membership(1L, "FLEET_MANAGER"), 7L, tokenRoles));
        // A plain WORKER token must not borrow a FLEET_MANAGER membership.
        assertFalse(enabled.isRoleEffective(Role.WORKER, membership(1L, "FLEET_MANAGER"), 7L,
                java.util.List.of("WORKER")));
        // A fleet-manager token with a normal WORKER membership is also valid.
        assertTrue(enabled.isRoleEffective(Role.WORKER, membership(1L, "WORKER"), 7L, tokenRoles));
        assertFalse(enabled.isRoleEffective(Role.WORKER, membership(null, "FLEET_MANAGER"), 7L, tokenRoles));
    }

    @Test
    void privilegedRolesFailClosedWhenSyncUnconfigured() {
        assertFalse(disabled.isRoleEffective(Role.DRIVER, membership(1L, "DRIVER"), 7L));
        assertFalse(disabled.isRoleEffective(Role.WORKER, membership(1L, "WORKER"), 7L));
        // Admin roles still pass through regardless of sync configuration.
        assertTrue(disabled.isRoleEffective(Role.ADMIN, null, 7L));
    }
}
