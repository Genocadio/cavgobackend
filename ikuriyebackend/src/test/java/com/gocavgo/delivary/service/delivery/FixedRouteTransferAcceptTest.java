package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.service.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for accepting FIXED_ROUTE packages via transfer.
 *
 * Before the fix, {@code acceptSinglePackage} always transitioned
 * CREATED → ACCEPTED, which is invalid for FIXED_ROUTE packages
 * (the valid transition is CREATED → ORIGIN_OFFICE). This caused:
 * {@code RuntimeException: Invalid status transition: CREATED -> ACCEPTED}
 */
@SpringBootTest
@Transactional
class FixedRouteTransferAcceptTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserJpaRepository userRepo;

    // ── Helpers ────────────────────────────────────────────────────────────

    private long nextUserId = 2_000_000L;

    private void authenticateAs(Long userId, Role role) {
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + role.name())
        );
        var auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null, authorities
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Long createUser(String email, Role role) {
        var user = UserEntity.builder()
                .id(nextUserId++)
                .email(email)
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        userRepo.save(user);
        return user.getId();
    }

    private PackageResponse createFixedRoutePackage(Long customerId, TransferRuleType ruleType) {
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                ruleType,
                null,
                null
        );
        var creation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        return packageService.getPackageById(creation.deliveryPackage().id());
    }

    private PackageResponse.CustodianResponse lastCustodian(PackageResponse pkg) {
        assertThat(pkg.custodians()).isNotEmpty();
        return pkg.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
    }

    // ── The core regression test ───────────────────────────────────────────

    @Test
    void fixedRouteAcceptTransition_goesToOriginOffice() {
        var customerId = createUser("fr-customer@test.com", Role.CUSTOMER);
        var workerId = createUser("fr-worker@test.com", Role.WORKER);

        // 1. Customer creates a FIXED_ROUTE package with an AUTO transfer → CREATED
        var created = createFixedRoutePackage(customerId, TransferRuleType.AUTO);
        assertThat(created.status()).isEqualTo(PackageStatus.CREATED);
        assertThat(created.deliveryType()).isEqualTo(DeliveryType.FIXED_ROUTE);
        assertThat(created.transfers()).hasSize(1);
        var transferId = created.transfers().get(0).id();

        // 2. Worker accepts the transfer
        //    Before the fix: FAILS with "Invalid status transition: CREATED -> ACCEPTED"
        //    After the fix:  SUCCEEDS, status becomes ORIGIN_OFFICE
        authenticateAs(workerId, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(workerId, transferId, null);

        var accepted = result.acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.ORIGIN_OFFICE);
        assertThat(lastCustodian(accepted).userId()).isEqualTo(workerId);
        assertThat(lastCustodian(accepted).role()).isEqualTo(CustodianRole.WORKER);

        // 3. The transfer is marked DONE
        assertThat(result.transfer().status()).isEqualTo(TransferStatus.DONE);
    }

    @Test
    void fixedRouteAcceptThenAdvanceToAssignedDriver() {
        var customerId = createUser("fr-customer2@test.com", Role.CUSTOMER);
        var workerId = createUser("fr-worker2@test.com", Role.WORKER);

        var created = createFixedRoutePackage(customerId, TransferRuleType.AUTO);
        var transferId = created.transfers().get(0).id();

        // Accept → ORIGIN_OFFICE
        authenticateAs(workerId, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(workerId, transferId, null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.ORIGIN_OFFICE);

        // Worker can now advance to IN_TRANSIT (valid FIXED_ROUTE transition from ORIGIN_OFFICE)
        authenticateAs(workerId, Role.WORKER);
        var inTransit = packageService.updateStatus(
                new UpdatePackageStatusInput(accepted.id(), workerId, PackageStatus.IN_TRANSIT, null));
        assertThat(inTransit.status()).isEqualTo(PackageStatus.IN_TRANSIT);
    }

    @Test
    void fixedRouteSecureAccept_goesToOriginOffice() {
        var customerId = createUser("fr-customer3@test.com", Role.CUSTOMER);
        var workerId = createUser("fr-worker3@test.com", Role.WORKER);

        var created = createFixedRoutePackage(customerId, TransferRuleType.SECURE);
        assertThat(created.transfers()).hasSize(1);
        var transferId = created.transfers().get(0).id();

        // Retrieve the raw transfer code that was generated during package creation
        var transfer = transferService.getTransferById(transferId);

        // For SECURE transfers the code is hashed — accept without code should fail
        authenticateAs(workerId, Role.WORKER);
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(workerId, transferId, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Transfer code is required");

        // Accepting with a wrong code should also fail
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(workerId, transferId, "WRONGCODE"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid transfer code");
    }

    @Test
    void fixedRouteWorkerCreatesTransferThenAnotherWorkerAccepts() {
        var customerId = createUser("fr-customer4@test.com", Role.CUSTOMER);
        var worker1Id = createUser("fr-worker4@test.com", Role.WORKER);
        var worker2Id = createUser("fr-worker5@test.com", Role.WORKER);

        // Customer creates a FIXED_ROUTE package with no transfer
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                null, // no auto-transfer
                null,
                null
        );
        var creation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();
        assertThat(creation.deliveryPackage().status()).isEqualTo(PackageStatus.CREATED);

        // Worker 1 creates an AUTO transfer for the package
        authenticateAs(worker1Id, Role.WORKER);
        var transfer = transferService.createTransfer(worker1Id,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));
        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);

        // Worker 2 accepts the transfer → ORIGIN_OFFICE
        authenticateAs(worker2Id, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(worker2Id, transfer.id(), null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.ORIGIN_OFFICE);
        assertThat(lastCustodian(accepted).userId()).isEqualTo(worker2Id);
    }

    @Test
    void openRouteDriverAccept_goesToPickedUp() {
        // Driver meets sender directly → package goes straight to PICKED_UP
        var customerId = createUser("or-customer@test.com", Role.CUSTOMER);
        var driverId = createUser("or-driver@test.com", Role.DRIVER);

        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();
        var transferId = creation.transfer().id();

        authenticateAs(driverId, Role.DRIVER);
        var result = packageService.acceptPackageByTransfer(driverId, transferId, null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();

        // OPEN_ROUTE + DRIVER acceptor → PICKED_UP (driver met sender directly)
        assertThat(accepted.status()).isEqualTo(PackageStatus.PICKED_UP);
        assertThat(lastCustodian(accepted).userId()).isEqualTo(driverId);
        assertThat(lastCustodian(accepted).role()).isEqualTo(CustodianRole.DRIVER);
    }

    @Test
    void openRouteWorkerAccept_stillGoesToAccepted() {
        // Worker accepts from sender → package is at office (ACCEPTED)
        var customerId = createUser("or-customer2@test.com", Role.CUSTOMER);
        var workerId = createUser("or-worker@test.com", Role.WORKER);

        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();
        var transferId = creation.transfer().id();

        authenticateAs(workerId, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(workerId, transferId, null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();

        // OPEN_ROUTE + WORKER acceptor → ACCEPTED (at office)
        assertThat(accepted.status()).isEqualTo(PackageStatus.ACCEPTED);
        assertThat(lastCustodian(accepted).userId()).isEqualTo(workerId);
        assertThat(lastCustodian(accepted).role()).isEqualTo(CustodianRole.WORKER);
    }

    @Test
    void fixedRouteAlreadyAcceptedPackage_cannotBeReAccepted() {
        var customerId = createUser("fr-customer5@test.com", Role.CUSTOMER);
        var worker1Id = createUser("fr-worker6@test.com", Role.WORKER);
        var worker2Id = createUser("fr-worker7@test.com", Role.WORKER);

        var created = createFixedRoutePackage(customerId, TransferRuleType.AUTO);
        var transferId = created.transfers().get(0).id();

        // Worker 1 accepts → ORIGIN_OFFICE
        authenticateAs(worker1Id, Role.WORKER);
        packageService.acceptPackageByTransfer(worker1Id, transferId, null);

        // Worker 1 creates a new transfer for the same package (now at ORIGIN_OFFICE)
        authenticateAs(worker1Id, Role.WORKER);
        var newTransfer = transferService.createTransfer(worker1Id,
                new CreateTransferInput(List.of(created.id()), TransferRuleType.AUTO, null, null, null));

        // Worker 2 accepts → stays ORIGIN_OFFICE (in-flight status preserved)
        authenticateAs(worker2Id, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(worker2Id, newTransfer.id(), null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.ORIGIN_OFFICE);
    }
}
