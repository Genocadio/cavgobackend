package com.gocavgo.delivary.controller.user;

import com.gocavgo.delivary.service.user.UserService;
import com.gocavgo.delivary.dto.user.input.AssignRoleInput;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class UserResolver {

    private static final Logger log = LoggerFactory.getLogger(UserResolver.class);
    private final UserService userService;

    @QueryMapping
    public List<UserResponse> users() {
        return userService.getAllUsers();
    }

    @QueryMapping
    public UserResponse user(@Argument Long id) {
        return userService.getUserById(id);
    }

    @QueryMapping
    public List<UserResponse> searchUsers(
            @Argument String query,
            @Argument com.gocavgo.delivary.enums.user.Role role,
            @Argument UUID companyId
    ) {
        return userService.searchUsers(query, role, companyId);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public UserResponse myProfile() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = Long.parseLong(authentication.getName());
        return userService.getUserById(userId);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public UserResponse syncUser() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        log.info("syncUser called with userId={}", userId);
        if (userId == null) {
            throw new IllegalStateException("Missing user id on authenticated request");
        }
        var response = userService.syncUser(userId);
        log.info("syncUser returning userId={}", response.id());
        return response;
    }

    @MutationMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public UserResponse assignRole(@Argument @Valid AssignRoleInput input) {
        return userService.assignRole(input);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public UserResponse disableUser(@Argument Long userId) {
        return userService.disableUser(userId);
    }

}
