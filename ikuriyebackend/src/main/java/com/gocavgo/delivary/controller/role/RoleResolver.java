package com.gocavgo.delivary.controller.role;

import com.gocavgo.delivary.dto.role.input.CreateRoleInput;
import com.gocavgo.delivary.dto.role.input.UpdateRoleInput;
import com.gocavgo.delivary.dto.role.output.RoleResponse;
import com.gocavgo.delivary.service.role.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL surface for managing the organisation's Nexxauth role definitions.
 * Reads are open to Admin/Super Admin; writes are Super Admin only (mirrors the
 * Nexxauth org API, where writes require the SUPER_USER / SUPER_ADMIN actor).
 */
@Controller
@RequiredArgsConstructor
public class RoleResolver {

    private final RoleService roleService;

    @QueryMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public List<RoleResponse> roles() {
        return roleService.listRoles();
    }

    @MutationMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public RoleResponse createRole(@Argument @Valid CreateRoleInput input) {
        return roleService.createRole(input);
    }

    @MutationMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public RoleResponse updateRole(@Argument @Valid UpdateRoleInput input) {
        return roleService.updateRole(input);
    }

    @MutationMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Boolean deleteRole(@Argument Long roleId) {
        roleService.deleteRole(roleId);
        return true;
    }
}
