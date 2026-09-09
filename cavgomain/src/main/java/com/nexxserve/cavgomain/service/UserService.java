package com.nexxserve.cavgomain.service;

import com.nexxserve.cavgomain.dto.response.CompanyUserResponseDto;
import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.CompanyUser;
import com.nexxserve.cavgomain.enums.CompanyUserRole;
import com.nexxserve.cavgomain.enums.UserStatus;
import com.nexxserve.cavgomain.repository.CompanyRepository;
import com.nexxserve.cavgomain.repository.CompanyUserRepository;
import com.nexxserve.cavgomain.security.NexxauthClient;
import com.nexxserve.cavgomain.security.NexxauthRoles;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CompanyUserRepository companyUserRepository;
    private final CompanyRepository companyRepository;
    private final NexxauthClient nexxauthClient;

    /**
     * Mirrors the authenticated user from Nexxauth into the local DB. Creates
     * the row when missing, updates profile fields when changed.
     */
    @Transactional
    public CompanyUserResponseDto syncUser(Long nexxauthUserId) {
        return syncUser(nexxauthUserId, null, null);
    }

    /**
     * Sync with an optional company code (for clients that know which company
     * the user belongs to) and optional dataHash (for JWT inline sync).
     */
    @Transactional
    public CompanyUserResponseDto syncUser(Long nexxauthUserId, String dataHash) {
        return syncUser(nexxauthUserId, dataHash, null);
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
     * This avoids a Nexxauth API call (network I/O + latency) in the common case;
     * only when the user is newly created or their profile changed in Nexxauth
     * (which updates the dataHash) does a sync occur.
     *
     * @param companyCode optional company code — when provided and the user is new,
     *                    the user is associated with that company instead of the fallback.
     */
    @Transactional
    public CompanyUserResponseDto syncUser(Long nexxauthUserId, String dataHash, String companyCode) {
        // Fast path: if the user exists locally and the dataHash matches, skip the
        // Nexxauth API call entirely.
        if (dataHash != null) {
            var existing = companyUserRepository.findById(nexxauthUserId).orElse(null);
            if (existing != null && dataHash.equals(existing.getDataHash())) {
                log.debug("syncUser: dataHash matches for userId={}, skipping Nexxauth call", nexxauthUserId);
                return CompanyUserResponseDto.fromEntity(existing);
            }
        }

        log.info("syncUser: starting for nexxauthUserId={}", nexxauthUserId);
        var nexxauthUser = nexxauthClient.getUser(nexxauthUserId);
        log.info("syncUser: Nexxauth returned user={} (enabled={}, roles={})",
                nexxauthUserId, nexxauthUser.enabled(), nexxauthUser.roles());

        var existing = companyUserRepository.findById(nexxauthUserId);

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

        // Create new user — resolve company by code if provided, otherwise fall back
        log.info("syncUser: creating new local user id={} (companyCode={})", nexxauthUserId, companyCode);
        var user = new CompanyUser();
        user.setId(nexxauthUserId);
        user.setFirstName(nexxauthUser.firstName());
        user.setLastName(nexxauthUser.lastName());
        user.setEmail(nexxauthUser.email());
        user.setPhone(nexxauthUser.phone());
        user.setStatus(status);
        user.setRole(role);
        if (dataHash != null) user.setDataHash(dataHash);

        Company company = null;
        if (companyCode != null && !companyCode.isBlank()) {
            company = companyRepository.findByCompanyCode(companyCode.trim()).orElse(null);
            if (company == null) {
                log.warn("syncUser: companyCode '{}' not found — falling back to first company", companyCode);
            }
        }
        if (company == null) {
            var companies = companyRepository.findAll();
            if (!companies.isEmpty()) {
                company = companies.get(0);
            }
        }
        if (company != null) {
            user.setCompany(company);
        }

        return CompanyUserResponseDto.fromEntity(companyUserRepository.save(user));
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
