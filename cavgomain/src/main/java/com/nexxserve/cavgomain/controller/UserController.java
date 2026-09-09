package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * User management endpoints — identity comes from Nexxauth, the backend
 * mirrors profiles locally via syncUser.
 */
@RestController
@RequestMapping("/main/users")
@RequiredArgsConstructor
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final UserService userService;

    /**
     * Syncs the authenticated user (identified by their Nexxauth org-user id)
     * from Nexxauth into the local DB. Creates the row when missing, updates
     * profile fields when changed.
     */
    @PostMapping("/sync")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CompanyUserResponseDto> syncUser() {
        var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        var userId = (Long) request.getAttribute("nexxauthUserId");
        log.info("syncUser called with userId={}", userId);
        if (userId == null) {
            throw new IllegalStateException("Missing user id on authenticated request");
        }
        var response = userService.syncUser(userId);
        log.info("syncUser returning userId={}", response.getId());
        return ResponseEntity.ok(response);
    }
}
