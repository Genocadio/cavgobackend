package com.gocavgo.delivary.service.user;

import com.gocavgo.delivary.dto.user.input.AssignRoleInput;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.mapper.user.UserMapper;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.repository.user.WorkerProfileRepository;
import com.gocavgo.delivary.security.NexxauthClient;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * User management, working hand in hand with Nexxauth.
 *
 * <p><b>Nexxauth is the source of truth for identity and roles.</b> The local
 * {@code users} table is a profile mirror used for business queries (search by
 * role, worker/driver profiles, package ownership). Users are registered
 * directly against Nexxauth by the apps (Android/web) — the backend never
 * creates them; it mirrors them locally via {@link #syncUser(Long)} and pushes
 * role/enable changes back through the SERVER client:
 * <ul>
 *   <li>In: {@link #syncUser(Long)} mirrors a user (profile + role) from Nexxauth
 *       after the client authenticates there.</li>
 *   <li>Out: {@link #assignRole} and {@link #disableUser} update the user in
 *       Nexxauth first (SERVER client), then mirror locally — so the next tokens
 *       issued to that user carry the new roles/status.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository userRepository;
    private final WorkerProfileRepository workerProfileRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final UserMapper userMapper;
    private final NexxauthClient nexxauthClient;
    private final StorageService storageService;
    private final PackageMediaJpaRepository mediaRepo;

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
     * The role is derived from the token's {@code roles} claim (not fetched from
     * Nexxauth) for the common case; only when the user is newly created or
     * updated does the role come from the Nexxauth response.
     */
    @Transactional
    public UserResponse syncUser(Long nexxauthUserId, String tokenDataHash) {
        var existing = userRepository.findById(nexxauthUserId).orElse(null);

        // Fast path: user exists and hash matches — no Nexxauth call needed.
        if (existing != null && tokenDataHash != null && tokenDataHash.equals(existing.getDataHash())) {
            log.debug("syncUser: hash match for userId={}, using cached data", nexxauthUserId);
            return toUserResponse(existing, resolveRoleFromToken(nexxauthUserId));
        }

        // Slow path: user missing or hash mismatch — fetch from Nexxauth.
        log.info("syncUser: {} for userId={} (tokenHash={}, localHash={})",
                existing == null ? "creating new user" : "hash mismatch — updating",
                nexxauthUserId, tokenDataHash, existing != null ? existing.getDataHash() : "null");

        var nexxauthUser = nexxauthClient.getUser(nexxauthUserId);
        log.info("syncUser: Nexxauth returned user={} (enabled={}, roles={})",
                nexxauthUserId, nexxauthUser.enabled(), nexxauthUser.roles());

        var role = NexxauthRoles.fromNexxauthNames(nexxauthUser.roles()).stream()
                .reduce(Role.CUSTOMER, (a, b) ->
                        precedence(b) > precedence(a) ? b : a);
        var status = nexxauthUser.enabled() ? UserStatus.ACTIVE : UserStatus.DISABLED;

        if (existing != null) {
            var user = existing;
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
            if (nexxauthUser.username() != null && !java.util.Objects.equals(nexxauthUser.username(), user.getUsername())) {
                user.setUsername(nexxauthUser.username());
                changed = true;
            }
            if (user.getStatus() != status) {
                log.info("syncUser: status changed {} -> {}", user.getStatus(), status);
                user.setStatus(status);
                changed = true;
            }
            // Always update the dataHash from the token.
            if (tokenDataHash != null && !tokenDataHash.equals(user.getDataHash())) {
                user.setDataHash(tokenDataHash);
                changed = true;
            }

            if (changed) {
                log.info("syncUser: saving updated user id={}", user.getId());
                return toUserResponse(userRepository.save(user), role);
            }
            return toUserResponse(user, role);
        }

        log.info("syncUser: creating new local user id={}", nexxauthUserId);
        var user = toEntity(nexxauthUser);
        user.setDataHash(tokenDataHash);
        return toUserResponse(userRepository.save(user), role);
    }

    /**
     * Legacy overload for backward compatibility — always fetches from Nexxauth.
     */
    @Transactional
    public UserResponse syncUser(Long nexxauthUserId) {
        return syncUser(nexxauthUserId, null);
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(user -> {
                    // Role is fetched from Nexxauth (source of truth), not the DB.
                    var role = resolveRoleFromNexxauth(id);
                    return toUserResponse(user, role);
                })
                .orElseThrow(() -> new RuntimeException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(user -> toUserResponse(user, resolveRoleFromNexxauth(user.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserResponse> searchUsers(String query, Role role, UUID companyId) {
        List<com.gocavgo.delivary.entity.user.UserEntity> users;
        if (companyId != null) {
            var userIds = new LinkedHashSet<Long>();
            workerProfileRepository.findByCompanyId(companyId)
                    .forEach(wp -> userIds.add(wp.getUser().getId()));
            driverProfileRepository.findByCompanyId(companyId)
                    .forEach(dp -> userIds.add(dp.getUser().getId()));
            if (userIds.isEmpty()) {
                return List.of();
            }
            users = userRepository.searchUsersByIds(new ArrayList<>(userIds), query);
        } else {
            users = userRepository.searchUsers(query);
        }
        // Resolve role from Nexxauth for each user, then filter by the requested role.
        // This ensures callers (e.g. driver picker) only see users of the requested type.
        return users.stream()
                .map(user -> toUserResponse(user, resolveRoleFromNexxauth(user.getId())))
                .filter(userResponse -> role == null || userResponse.role() == role)
                .toList();
    }

    /**
     * Assigns a role to an existing user. Works hand in hand with Nexxauth:
     * <ol>
     *   <li>The user must already exist in Nexxauth (the apps register users
     *       directly there — the backend never provisions them). If only the
     *       local mirror is missing, it is created from the Nexxauth profile.</li>
     *   <li>The new role is pushed to Nexxauth ({@code PATCH /users/{id}}), so the
     *       user's next tokens carry it.</li>
     *   <li>The local mirror is updated (role + WORKER office profile).</li>
     * </ol>
     */
    @Transactional
    public UserResponse assignRole(AssignRoleInput input) {
        var userId = input.userId();

        var user = userRepository.findById(userId).orElse(null);

        // Users are created by the apps directly in Nexxauth; a missing local row
        // just means the mirror hasn't been created yet — build it from Nexxauth.
        if (user == null) {
            var nexxauthUser = findInNexxauth(userId);
            if (nexxauthUser == null) {
                throw new RuntimeException("User not found in Nexxauth: " + userId);
            }
            log.info("assignRole: mirroring existing Nexxauth user id={}", userId);
            user = userRepository.save(toEntity(nexxauthUser));
        }

        // Push the role to Nexxauth so future tokens carry it.
        // Roles are no longer stored locally — the DB is a profile mirror only.
        nexxauthClient.updateUserRoles(userId, List.of(NexxauthRoles.toNexxauthName(input.role())));

        var saved = userRepository.save(user);

        if (input.role() == Role.WORKER) {
            upsertWorkerProfile(saved);
        } else {
            // Leaving the WORKER role — drop the profile.
            workerProfileRepository.findByUserId(saved.getId())
                    .ifPresent(profile -> {
                        log.info("assignRole: removing worker profile id={} for user={} (new role={})",
                                profile.getId(), saved.getId(), input.role());
                        workerProfileRepository.deleteById(profile.getId());
                    });
        }

        return toUserResponse(saved, input.role());
    }

    @Transactional
    public UserResponse disableUser(Long userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        // Disable in Nexxauth first (source of truth), then mirror locally.
        nexxauthClient.setUserEnabled(userId, false);
        user.setStatus(UserStatus.DISABLED);
        var saved = userRepository.save(user);
        return toUserResponse(saved, resolveRoleFromNexxauth(userId));
    }

    @Transactional
    public UserResponse updateAvatarByMediaId(Long userId, String mediaId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));
        UUID mediaUuid;
        try {
            mediaUuid = UUID.fromString(mediaId);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid mediaId format: " + mediaId);
        }
        var media = mediaRepo.findById(mediaUuid)
                .orElseThrow(() -> new RuntimeException("Media not found: " + mediaId));
        user.setAvatarStoragePath(media.getStoragePath());
        user.setAvatarBucket(media.getBucket());
        user.setAvatarStorageMode(media.getStorageMode());
        var saved = userRepository.save(user);
        log.info("updateAvatar: user={}, mediaId={}, bucket={}, path={}", userId, mediaId, media.getBucket(), media.getStoragePath());
        return toUserResponse(saved, resolveRoleFromNexxauth(userId));
    }

    public UUID getCompanyIdForUser(Long userId) {
        var worker = workerProfileRepository.findByUserId(userId);
        if (worker.isPresent()) {
            return worker.get().getCompanyId();
        }
        var driver = driverProfileRepository.findByUserId(userId);
        return driver.map(d -> d.getCompanyId()).orElse(null);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private NexxauthClient.OrgUser findInNexxauth(Long userId) {
        try {
            return nexxauthClient.getUser(userId);
        } catch (NexxauthClient.NexxauthApiException e) {
            if (e.getStatus() == 404) return null;
            throw e;
        }
    }

    private UserEntity toEntity(NexxauthClient.OrgUser nexxauthUser) {
        return UserEntity.builder()
                .id(nexxauthUser.id())
                .email(nexxauthUser.email() != null ? nexxauthUser.email() : "")
                .phone(nexxauthUser.phone())
                .firstName(nexxauthUser.firstName())
                .lastName(nexxauthUser.lastName())
                .username(nexxauthUser.username())
                .status(nexxauthUser.enabled() ? UserStatus.ACTIVE : UserStatus.DISABLED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private void upsertWorkerProfile(UserEntity user) {
        var existing = workerProfileRepository.findByUserId(user.getId());
        if (existing.isPresent()) {
            var profile = existing.get();
            workerProfileRepository.save(profile);
            log.info("upsertWorkerProfile: updated worker profile id={} for user={}",
                    profile.getId(), user.getId());
            return;
        }
        var profile = workerProfileRepository.save(WorkerProfileEntity.builder()
                .user(user)
                .createdAt(Instant.now())
                .build());
        log.info("upsertWorkerProfile: created worker profile id={} for user={}",
                profile.getId(), user.getId());
    }

    private static int precedence(Role role) {
        return switch (role) {
            case SUPER_ADMIN -> 5;
            case ADMIN -> 4;
            case WORKER -> 3;
            case DRIVER -> 2;
            case CUSTOMER -> 1;
        };
    }

    private UserResponse toUserResponse(UserEntity user, Role role) {
        var base = userMapper.toResponse(user, role);
        // Resolve avatar URL from storage path — handles both local and Supabase
        if (user.getAvatarStoragePath() != null && user.getAvatarBucket() != null) {
            boolean isLocal = "local".equals(user.getAvatarStorageMode());
            var avatarUrl = storageService.getFileUrl(user.getAvatarBucket(), user.getAvatarStoragePath(), isLocal);
            if (avatarUrl != null) {
                return new UserResponse(
                        base.id(), base.email(), base.phone(),
                        base.firstName(), base.lastName(), base.username(),
                        avatarUrl,
                        base.role(), base.status(),
                        user.getDataHash(),
                        base.createdAt(), base.updatedAt()
                );
            }
        }
        return new UserResponse(
                base.id(), base.email(), base.phone(),
                base.firstName(), base.lastName(), base.username(),
                base.avatarUrl(),
                base.role(), base.status(),
                user.getDataHash(),
                base.createdAt(), base.updatedAt()
        );
    }

    /**
     * Resolves the user's role from the JWT token's roles claim (stored in the
     * security context by the filter). This avoids a Nexxauth API call on every
     * request — roles are already in the token.
     */
    private Role resolveRoleFromToken(Long userId) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities() != null) {
            return authentication.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .filter(a -> a.startsWith("ROLE_"))
                    .map(a -> a.substring(5))
                    .map(NexxauthRoles::fromNexxauthName)
                    .filter(r -> r != null)
                    .reduce((a, b) -> precedence(b) > precedence(a) ? b : a)
                    .orElse(Role.CUSTOMER);
        }
        return Role.CUSTOMER;
    }

    /**
     * Fetches the user's role from Nexxauth. Returns CUSTOMER as a safe default
     * if the Nexxauth call fails (e.g. user not yet provisioned).
     */
    private Role resolveRoleFromNexxauth(Long userId) {
        try {
            var nexxauthUser = nexxauthClient.getUser(userId);
            return NexxauthRoles.fromNexxauthNames(nexxauthUser.roles()).stream()
                    .reduce(Role.CUSTOMER, (a, b) ->
                            precedence(b) > precedence(a) ? b : a);
        } catch (Exception e) {
            log.warn("resolveRoleFromNexxauth: failed for userId={}, falling back to CUSTOMER: {}",
                    userId, e.getMessage());
            return Role.CUSTOMER;
        }
    }
}
