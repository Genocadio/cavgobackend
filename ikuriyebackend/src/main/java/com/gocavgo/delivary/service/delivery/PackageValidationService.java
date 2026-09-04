package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.user.DriverProfileRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PackageValidationService {

    private final UserRepository userRepository;
    private final DriverProfileRepository driverProfileRepository;

    private static final Map<PackageStatus, Set<PackageStatus>> OPEN_TRANSITIONS = Map.of(
            PackageStatus.CREATED, Set.of(PackageStatus.ACCEPTED, PackageStatus.CANCELLED),
            PackageStatus.ACCEPTED, Set.of(PackageStatus.PICKED_UP, PackageStatus.PENDING_CONFIRMATION, PackageStatus.CANCELLED),
            PackageStatus.PICKED_UP, Set.of(PackageStatus.IN_TRANSIT, PackageStatus.CANCELLED),
            PackageStatus.IN_TRANSIT, Set.of(PackageStatus.PENDING_CONFIRMATION, PackageStatus.CANCELLED),
            PackageStatus.PENDING_CONFIRMATION, Set.of(PackageStatus.DELIVERED, PackageStatus.CANCELLED),
            PackageStatus.DELIVERED, Set.of(PackageStatus.COMPLETED, PackageStatus.CANCELLED),
            PackageStatus.COMPLETED, Set.of(),
            PackageStatus.CANCELLED, Set.of()
    );

    @SuppressWarnings("unchecked")
    private static final Map<PackageStatus, Set<PackageStatus>> ROUTE_TRANSITIONS = Map.ofEntries(
            Map.entry(PackageStatus.CREATED, Set.of(PackageStatus.ORIGIN_OFFICE, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.ACCEPTED, Set.of(PackageStatus.ORIGIN_OFFICE, PackageStatus.ASSIGNED_DRIVER, PackageStatus.IN_TRANSIT, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.ORIGIN_OFFICE, Set.of(PackageStatus.ASSIGNED_DRIVER, PackageStatus.IN_TRANSIT, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.ASSIGNED_DRIVER, Set.of(PackageStatus.IN_TRANSIT, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.IN_TRANSIT, Set.of(PackageStatus.DESTINATION_OFFICE, PackageStatus.PENDING_CONFIRMATION, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.DESTINATION_OFFICE, Set.of(PackageStatus.READY_FOR_COLLECTION, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.READY_FOR_COLLECTION, Set.of(PackageStatus.PENDING_CONFIRMATION, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.PENDING_CONFIRMATION, Set.of(PackageStatus.DELIVERED, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.DELIVERED, Set.of(PackageStatus.COMPLETED, PackageStatus.CANCELLED)),
            Map.entry(PackageStatus.COMPLETED, Set.of()),
            Map.entry(PackageStatus.CANCELLED, Set.of())
    );

    private static final Set<Role> CREATOR_ROLES = Set.of(Role.CUSTOMER, Role.WORKER, Role.DRIVER);
    private static final Set<Role> ACCEPTOR_ROLES = Set.of(Role.WORKER, Role.DRIVER);
    private static final Set<Role> ASSIGNER_ROLES = Set.of(Role.WORKER, Role.DRIVER, Role.ADMIN, Role.SUPER_ADMIN);

    public void validateCreator(Long userId, Role role) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessValidationException("Creator not found: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessValidationException("Creator is not ACTIVE");
        }
        if (!CREATOR_ROLES.contains(role)) {
            throw new BusinessValidationException("User role cannot create packages: " + role);
        }
    }

    public void validateAcceptor(Long userId, Role role) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessValidationException("Acceptor not found: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessValidationException("Acceptor is not ACTIVE");
        }
        if (!ACCEPTOR_ROLES.contains(role)) {
            throw new BusinessValidationException("User role cannot accept packages: " + role);
        }
    }

    /** Returns the role of the acceptor (WORKER or DRIVER). Call after validateAcceptor. */
    public Role resolveAcceptorRole(Long userId, Role role) {
        return role;
    }

    public void validateDriver(Long driverId) {
        var user = userRepository.findById(driverId)
                .orElseThrow(() -> new BusinessValidationException("Driver not found: " + driverId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessValidationException("Driver is not ACTIVE");
        }
        driverProfileRepository.findByUserId(driverId)
                .orElseThrow(() -> new BusinessValidationException("Driver profile not found"));
    }

    public void validateAssigner(Long userId, Role role) {
        if (!ASSIGNER_ROLES.contains(role)) {
            throw new BusinessValidationException("User role cannot assign drivers: " + role);
        }
    }

    public void validateTransition(PackageStatus current, PackageStatus next, DeliveryType deliveryType) {
        var transitions = deliveryType == DeliveryType.FIXED_ROUTE ? ROUTE_TRANSITIONS : OPEN_TRANSITIONS;
        var allowed = transitions.get(current);
        if (allowed == null || !allowed.contains(next)) {
            throw new BusinessValidationException("Invalid status transition: " + current + " -> " + next);
        }
    }

    public List<PackageStatus> getNextAllowedStates(PackageStatus current, DeliveryType deliveryType) {
        var transitions = deliveryType == DeliveryType.FIXED_ROUTE ? ROUTE_TRANSITIONS : OPEN_TRANSITIONS;
        return transitions.getOrDefault(current, Set.of()).stream().toList();
    }

    /**
     * Validates that the actor's system role is allowed by the transfer's acceptorType.
     */
    public void validateAcceptorType(TransferAcceptorType acceptorType, Role actorRole, UUID transferId) {
        var ctx = transferId != null ? "Transfer " + transferId : "This transfer";
        if (acceptorType == TransferAcceptorType.WORKER && actorRole != Role.WORKER) {
            throw new BusinessValidationException(ctx + " requires WORKER role, but actor has " + actorRole);
        }
        if (acceptorType == TransferAcceptorType.DRIVER && actorRole != Role.DRIVER) {
            throw new BusinessValidationException(ctx + " requires DRIVER role, but actor has " + actorRole);
        }
        // BOTH allows both WORKER and DRIVER — no validation needed
    }
}
