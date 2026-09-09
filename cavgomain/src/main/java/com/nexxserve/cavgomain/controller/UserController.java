package com.nexxserve.cavgomain.controller;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.UserResponseDto;
import com.nexxserve.cavgomain.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

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
     * Returns all users (both CompanyUser and ClientUser). Supports optional
     * time-based filtering for incremental sync and name search.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<UserResponseDto>> getAllUsers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timeLimit,
            @RequestParam(required = false) String name) {
        List<UserResponseDto> users;
        if (name != null && !name.trim().isEmpty()) {
            users = userService.searchUsersByName(name.trim(), timeLimit);
        } else {
            users = userService.findAllUsers(timeLimit);
        }
        return ResponseEntity.ok(users);
    }

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
