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
import com.gocavgo.delivary.dto.delivery.output.DeliveryCodeResult;
import com.gocavgo.delivary.dto.delivery.output.DeliveryPackagePage;
import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
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
        return packageService.getAvailablePackages(status, order != null ? order : SortOrder.ASC, page, size);
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

        // Resolve sender: auto-fill from creator profile when omitted (CUSTOMER only),
        // or enrich with profile data if userId provided but name/phone missing.
        CreatePackageInput.PersonInput sender = input.sender();
        if (sender == null) {
            if (role == Role.CUSTOMER) {
                sender = buildPersonFromUser(creatorId, PersonRole.SENDER);
            } else {
                throw new RuntimeException("Sender information is required");
            }
        } else {
            sender = enrichPerson(sender);
        }

        // Resolve receiver: enrich with profile data if userId provided but name/phone missing.
        var receiver = enrichPerson(input.receiver());

        var companyId = role != Role.CUSTOMER ? userService.getCompanyIdForUser(creatorId) : null;

        var resolvedInput = new CreatePackageInput(
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

        return packageService.createPackage(creatorId, role, companyId, resolvedInput);
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
                input.notes()
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
        return packageService.confirmDelivery(authenticatedUserId, input.packageId(), input.deliveryCode());
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
