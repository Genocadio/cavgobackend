package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.dto.response.UserResponseDto;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.entity.User;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.repository.UserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.security.NexxauthRoles;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * User management, working hand in hand with Nexxauth.
 *
 * <p><b>Nexxauth is the source of truth for identity and roles.</b> The local
 * user rows are profile mirrors used for business queries. Users are registered
 * directly against Nexxauth by the apps — the backend mirrors them locally via
 * {@link #syncUser(Long)}.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository userRepository;
    private final CompanyUserRepository companyUserRepository;
    private final NexxauthClient nexxauthClient;
    private final EntityManager entityManager;

    /**
     * Mirrors the authenticated user from Nexxauth into the local DB. Creates
     * the row when missing, updates profile fields when changed.
     */
    @Transactional
    public CompanyUserResponseDto syncUser(Long nexxauthUserId) {
        return syncUser(nexxauthUserId, null);
    }

    /**
     * Ensures the local user mirror is up-to-date with Nexxauth. Uses the
     * {@code dataHash} from the JWT token to detect stale data without hitting
     * Nexxauth on every request:
     * <ul>
     *   <li>If the user doesn't exist locally → fetch from Nexxauth and create.</li>
     *   <li>If the stored {@code dataHash} is null or differs from the token's
     *       hash → fetch from Nexxauth and update.</li>
     *   <li>If the hash matches → return the cached local user (no Nexxauth call).</li>
     * </ul>
     *
     * @param nexxauthUserId the Nexxauth user id to sync
     * @param dataHash optional dataHash from the JWT — when provided and matching
     *                 the stored hash the Nexxauth call is skipped
     */
    @Transactional
    public CompanyUserResponseDto syncUser(Long nexxauthUserId, String dataHash) {
        var existing = companyUserRepository.findById(nexxauthUserId);

        // Fast path: if the user exists locally and the dataHash matches, skip the
        // Nexxauth API call entirely.
        if (dataHash != null && existing.isPresent()) {
            if (dataHash.equals(existing.get().getDataHash())) {
                log.debug("syncUser: dataHash matches for userId={}, skipping Nexxauth call", nexxauthUserId);
                return CompanyUserResponseDto.fromEntity(existing.get());
            }
        }

        log.info("syncUser: starting for nexxauthUserId={}", nexxauthUserId);
        var nexxauthUser = nexxauthClient.getUser(nexxauthUserId);
        log.info("syncUser: Nexxauth returned user={} (enabled={}, roles={})",
                nexxauthUserId, nexxauthUser.enabled(), nexxauthUser.roles());

        var status = nexxauthUser.enabled() ? UserStatus.ACTIVE : UserStatus.INACTIVE;

        // Derive the company role from Nexxauth roles
        var role = NexxauthRoles.fromNexxauthNames(nexxauthUser.roles()).stream()
                .reduce(CompanyUserRole.DRIVER, (a, b) ->
                        precedence(b) > precedence(a) ? b : a);

        if (existing.isPresent()) {
            var user = existing.get();
            boolean changed = false;

            if (nexxauthUser.email() != null && !nexxauthUser.email().equals(user.getEmail())) {
                user.setEmail(nexxauthUser.email());
                changed = true;
            }
            if (nexxauthUser.phone() != null && !java.util.Objects.equals(nexxauthUser.phone(), user.getPhone())) {
                user.setPhone(nexxauthUser.phone());
                changed = true;
            }
            if (nexxauthUser.firstName() != null && !java.util.Objects.equals(nexxauthUser.firstName(), user.getFirstName())) {
                user.setFirstName(nexxauthUser.firstName());
                changed = true;
            }
            if (nexxauthUser.lastName() != null && !java.util.Objects.equals(nexxauthUser.lastName(), user.getLastName())) {
                user.setLastName(nexxauthUser.lastName());
                changed = true;
            }
            if (user.getStatus() != status) {
                log.info("syncUser: status changed {} -> {}", user.getStatus(), status);
                user.setStatus(status);
                changed = true;
            }
            if (user.getRole() != role) {
                log.info("syncUser: role changed {} -> {}", user.getRole(), role);
                user.setRole(role);
                changed = true;
            }

            // Always update the dataHash if provided, even if no other fields changed
            if (dataHash != null && !dataHash.equals(user.getDataHash())) {
                user.setDataHash(dataHash);
                changed = true;
            }

            if (changed) {
                log.info("syncUser: saving updated user id={}", user.getId());
                return CompanyUserResponseDto.fromEntity(companyUserRepository.save(user));
            }
            log.info("syncUser: no changes detected for user id={}", user.getId());
            return CompanyUserResponseDto.fromEntity(user);
        }

        // Create new user
        log.info("syncUser: creating new local user id={}", nexxauthUserId);
        var user = new CompanyUser();
        user.setId(nexxauthUserId);
        user.setFirstName(nexxauthUser.firstName());
        user.setLastName(nexxauthUser.lastName());
        user.setEmail(nexxauthUser.email());
        user.setPhone(nexxauthUser.phone());
        user.setStatus(status);
        user.setRole(role);
        if (dataHash != null) user.setDataHash(dataHash);

        // Use persist() — the id is pre-assigned (Nexxauth user id), so save()
        // would treat this as an existing entity and attempt an UPDATE on a row
        // that does not exist yet ("Row was updated or deleted by another
        // transaction"). persist() always issues an INSERT for a new entity.
        entityManager.persist(user);
        return CompanyUserResponseDto.fromEntity(user);
    }

    /**
     * Returns all users (both CompanyUser and ClientUser). When a timeLimit is
     * provided only users created or updated after that time are returned (for
     * incremental sync).
     */
    @Transactional(readOnly = true)
    public List<UserResponseDto> findAllUsers(LocalDateTime timeLimit) {
        List<User> users;
        if (timeLimit != null) {
            users = userRepository.findAllAfterTime(timeLimit);
        } else {
            users = userRepository.findAllAfterTime(LocalDateTime.of(1970, 1, 1, 0, 0));
        }
        return users.stream()
                .map(UserResponseDto::fromEntity)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Searches users by name (first name or last name). When a timeLimit is
     * provided the search is restricted to records changed after that time.
     */
    @Transactional(readOnly = true)
    public List<UserResponseDto> searchUsersByName(String name, LocalDateTime timeLimit) {
        List<User> users;
        if (timeLimit != null) {
            users = userRepository.findByNameContainingAfterTime(name, timeLimit);
        } else {
            users = userRepository.findByNameContaining(name);
        }
        return users.stream()
                .map(UserResponseDto::fromEntity)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static int precedence(CompanyUserRole role) {
        return switch (role) {
            case DRIVER -> 1;
            case FLEET_MANAGER -> 2;
            case SUPERVISOR -> 3;
            case ADMIN -> 4;
        };
    }
}
