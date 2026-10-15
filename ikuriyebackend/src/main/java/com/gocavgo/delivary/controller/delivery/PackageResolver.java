package com.gocavgo.delivary.controller.delivery;

import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.user.UserService;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.service.delivery.PackageService;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.delivery.SortOrder;
import com.gocavgo.delivary.dto.delivery.input.AssignDriverInput;
import com.gocavgo.delivary.dto.delivery.input.AssignPackageCompanyInput;
import com.gocavgo.delivary.dto.delivery.input.AssignPackageTripInput;
import com.gocavgo.delivary.dto.delivery.input.ConfirmDeliveryInput;
import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.InitiateDeliveryInput;
import com.gocavgo.delivary.dto.delivery.input.RegenerateDeliveryCodeInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.delivery.output.CatchupSyncResult;
import com.gocavgo.delivary.dto.delivery.output.DeliveryCodeResult;
import com.gocavgo.delivary.dto.delivery.output.DeliveryPackagePage;
import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.exception.BusinessValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class PackageResolver {

    private final PackageService packageService;
    private final UserService userService;
    private final com.gocavgo.delivary.service.idempotency.IdempotencyService idempotencyService;
    private final com.gocavgo.delivary.controller.graphql.NoticeResolver noticeResolver;

    @QueryMapping("package")
    public PackageResponse packageQuery(@Argument UUID id) {
        return packageService.getPackageById(id);
    }

    @QueryMapping
    public PackageResponse packageByTrackingCode(@Argument String code) {
        return packageService.getPackageByTrackingCode(code);
    }

    @QueryMapping
    public DeliveryPackagePage packagesByCreator(
            @Argument Long creatorId,
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        return packageService.getPackagesByCreator(creatorId, status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    public DeliveryPackagePage packagesByDriver(
            @Argument Long driverId,
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        return packageService.getPackagesByDriver(driverId, status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    public DeliveryPackagePage packagesByStatus(
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        return packageService.getPackagesByStatus(status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    public DeliveryPackagePage availablePackages(
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        Long currentUserId = null;
        Role currentRole = null;
        java.util.UUID currentCompanyId = null;
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) {
                currentUserId = Long.parseLong(auth.getName());
                currentRole = NexxauthRoles.primaryRole(auth.getAuthorities());
                currentCompanyId = userService.getCompanyIdForUser(currentUserId);
            }
        } catch (Exception ignored) {
            // Not authenticated — proceed without user context (legacy callers)
        }
        return packageService.getAvailablePackages(
                status, order != null ? order : SortOrder.ASC, page, size,
                currentUserId, currentRole, currentCompanyId);
    }

    @QueryMapping
    public DeliveryPackagePage packagesByCustodian(
            @Argument CustodianRole role,
            @Argument Long custodianId,
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        return packageService.getPackagesByCustodian(role, custodianId, status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    public DeliveryPackagePage packagesByUser(
            @Argument Long userId,
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        return packageService.getAllPackagesByCustodian(userId, status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public DeliveryPackagePage myPackages(
            @Argument PackageStatus status,
            @Argument SortOrder order,
            @Argument int page,
            @Argument int size
    ) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = Long.parseLong(authentication.getName());
        var role = NexxauthRoles.primaryRole(authentication.getAuthorities());
        var phone = role == Role.CUSTOMER ? userService.getUserById(userId).phone() : null;
        return packageService.getMyPackages(userId, phone, role, status, order != null ? order : SortOrder.ASC, page, size);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public CatchupSyncResult syncSince(@Argument String since) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var userId = Long.parseLong(authentication.getName());
        var role = NexxauthRoles.primaryRole(authentication.getAuthorities());
        var phone = role == Role.CUSTOMER ? userService.getUserById(userId).phone() : null;
        UUID companyId = null;

        java.time.Instant sinceInstant;
        try {
            sinceInstant = java.time.Instant.parse(since);
        } catch (Exception e) {
            sinceInstant = java.time.Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS);
        }

        var packages = packageService.getPackagesModifiedSince(userId, phone, role, companyId, sinceInstant);
        var notices = noticeResolver.getNoticesSince(userId, sinceInstant);

        return new CatchupSyncResult(packages, notices, java.time.Instant.now().toString());
    }

    @QueryMapping
    public List<PackageResponse.EventResponse> packageHistory(@Argument UUID packageId) {
        return packageService.getPackageHistory(packageId);
    }

    @QueryMapping
    public List<PackageResponse.CustodyResponse> packageCustody(@Argument UUID packageId) {
        return packageService.getPackageCustodyHistory(packageId);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','WORKER','DRIVER')")
    public PackageCreationResponse createPackage(@Argument @Valid CreatePackageInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var creatorId = Long.parseLong(authentication.getName());
        var role = NexxauthRoles.primaryRole(authentication.getAuthorities());

        // Check idempotency cache first
        var cached = idempotencyService.getCachedResponse(
                input.idempotencyKey(), creatorId, "createPackage", PackageCreationResponse.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        // Resolve sender: auto-fill from creator profile when omitted (CUSTOMER only),
        // or enrich with profile data if userId provided but name/phone missing.
        CreatePackageInput.PersonInput sender = input.sender();
        if (sender == null) {
            if (role == Role.CUSTOMER) {
                sender = buildPersonFromUser(creatorId, PersonRole.SENDER);
            } else {
                throw new BusinessValidationException("Sender information is required");
            }
        } else {
            sender = enrichPerson(sender);
        }

        // Resolve receiver: enrich with profile data if userId provided but name/phone missing.
        var receiver = enrichPerson(input.receiver());

        UUID companyId = null;

        var resolvedInput = new CreatePackageInput(
                input.idempotencyKey(),
                input.deliveryType(),
                sender,
                receiver,
                input.origin(),
                input.destination(),
                input.details(),
                input.transferRuleType(),
                input.transferMatchCompanyId(),
                input.transferMatchUserId()
        );

        var response = packageService.createPackage(creatorId, role, companyId, resolvedInput);
        idempotencyService.recordResponse(input.idempotencyKey(), creatorId, "createPackage", response);
        return response;
    }

    /**
     * Builds a PersonInput fully populated from the registered user record.
     * Used when the caller provides no sender at all (CUSTOMER auto-fill).
     */
    private CreatePackageInput.PersonInput buildPersonFromUser(Long userId, PersonRole role) {
        var user = userService.getUserById(userId);
        var name = buildFullName(user.firstName(), user.lastName());
        return new CreatePackageInput.PersonInput(role, userId, name, user.phone());
    }

    /**
     * Resolves the final PersonInput according to these rules:
     *
     * | userId | name | phone | Result
     * |--------|------|-------|-------
     * | absent | any  | any   | Anonymous — store name + phone exactly as passed.
     * | given  | absent | absent | Fetch name AND phone from user profile.
     * | given  | absent | given  | Fetch name from user profile, use passed phone.
     * | given  | given  | absent | Use passed name, fetch phone from user profile.
     * | given  | given  | given  | Use passed name and passed phone (userId just links the record).
     *
     * A userId without a resolvable name is rejected — we never store userId + null name.
     */
    private CreatePackageInput.PersonInput enrichPerson(CreatePackageInput.PersonInput person) {
        if (person == null) return null;

        if (person.userId() == null) {
            // Anonymous — store exactly what was passed, no lookup.
            return person;
        }

        boolean hasName  = person.name()  != null && !person.name().isBlank();
        boolean hasPhone = person.phone() != null && !person.phone().isBlank();

        // If both name and phone are already provided, just link userId as-is.
        if (hasName && hasPhone) {
            return person;
        }

        // At least one field needs resolving — fetch the user profile.
        var user = userService.getUserById(person.userId());

        // Resolve name: caller-supplied takes precedence; fall back to profile.
        String name;
        if (hasName) {
            name = person.name().strip();
        } else {
            name = buildFullName(user.firstName(), user.lastName());
            if (name == null) {
                // Profile has no name either — use email prefix as last resort so
                // we never store userId + null name.
                name = user.email() != null
                        ? user.email().split("@")[0]
                        : person.userId().toString();
            }
        }

        // Resolve phone: caller-supplied takes precedence; fall back to profile.
        String phone = hasPhone ? person.phone() : user.phone();

        return new CreatePackageInput.PersonInput(person.role(), person.userId(), name, phone);
    }

    private String buildFullName(String firstName, String lastName) {
        var name = (firstName != null ? firstName : "")
                + (lastName != null && !lastName.isBlank() ? " " + lastName : "");
        return name.isBlank() ? null : name.strip();
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','DRIVER','ADMIN','SUPER_ADMIN')")
    public PackageResponse assignDriver(@Argument @Valid AssignDriverInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUserId = Long.parseLong(authentication.getName());
        // Override assignedBy with the authenticated user's ID
        var resolvedInput = new AssignDriverInput(
                input.packageId(),
                input.driverId(),
                authenticatedUserId,
                input.notes(),
                input.tripId()
        );
        return packageService.assignDriver(resolvedInput);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','DRIVER','ADMIN','SUPER_ADMIN')")
    public PackageResponse updatePackageStatus(@Argument @Valid UpdatePackageStatusInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUserId = Long.parseLong(authentication.getName());
        // Override actorId with the authenticated user's ID
        var resolvedInput = new UpdatePackageStatusInput(
                input.packageId(),
                authenticatedUserId,
                input.status(),
                input.notes()
        );
        return packageService.updateStatus(resolvedInput);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','DRIVER')")
    public DeliveryCodeResult initiateDelivery(@Argument @Valid InitiateDeliveryInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUserId = Long.parseLong(authentication.getName());
        return packageService.initiateDelivery(authenticatedUserId, input.packageId());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public PackageResponse confirmDelivery(@Argument @Valid ConfirmDeliveryInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUserId = Long.parseLong(authentication.getName());

        var cached = idempotencyService.getCachedResponse(
                input.idempotencyKey(), authenticatedUserId, "confirmDelivery", PackageResponse.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        var response = packageService.confirmDelivery(authenticatedUserId, input.packageId(), input.deliveryCode());
        idempotencyService.recordResponse(input.idempotencyKey(), authenticatedUserId, "confirmDelivery", response);
        return response;
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','DRIVER')")
    public DeliveryCodeResult regenerateDeliveryCode(@Argument @Valid RegenerateDeliveryCodeInput input) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var authenticatedUserId = Long.parseLong(authentication.getName());
        return packageService.regenerateDeliveryCode(authenticatedUserId, input.packageId());
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','ADMIN','SUPER_ADMIN')")
    public PackageResponse assignPackageCompany(@Argument @Valid AssignPackageCompanyInput input) {
        return packageService.assignPackageCompany(input);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('WORKER','ADMIN','SUPER_ADMIN')")
    public PackageResponse assignPackageTrip(@Argument @Valid AssignPackageTripInput input) {
        return packageService.assignPackageTrip(input);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean deletePackage(@Argument UUID packageId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var userId = Long.parseLong(auth.getName());
        return packageService.deletePackage(userId, packageId);
    }
}
