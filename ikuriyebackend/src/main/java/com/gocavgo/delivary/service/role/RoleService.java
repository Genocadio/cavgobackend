package com.gocavgo.delivary.service.role;

import com.gocavgo.delivary.dto.role.input.CreateRoleInput;
import com.gocavgo.delivary.dto.role.input.UpdateRoleInput;
import com.gocavgo.delivary.dto.role.output.RoleResponse;
import com.gocavgo.delivary.security.NexxauthClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages the organisation's role definitions in Nexxauth (the SERVER client is
 * the only actor allowed to write them). Roles defined here flow into user
 * tokens; the backend maps role names back to local {@code ROLE_*} authorities
 * via {@code NexxauthRoles}. Permissions are resolved server-side by Nexxauth.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final NexxauthClient nexxauthClient;

    public List<RoleResponse> listRoles() {
        return nexxauthClient.listRoles().stream().map(this::toResponse).toList();
    }

    public RoleResponse createRole(CreateRoleInput input) {
        return toResponse(nexxauthClient.createRole(
                input.name(), input.permissions(), input.isDefault()));
    }

    public RoleResponse updateRole(UpdateRoleInput input) {
        return toResponse(nexxauthClient.updateRole(
                input.roleId(), input.name(), input.permissions(), input.isDefault()));
    }

    public void deleteRole(Long roleId) {
        nexxauthClient.deleteRole(roleId);
    }

    private RoleResponse toResponse(NexxauthClient.OrgRole role) {
        return new RoleResponse(role.id(), role.name(), role.permissions(), role.isDefault());
    }
}
