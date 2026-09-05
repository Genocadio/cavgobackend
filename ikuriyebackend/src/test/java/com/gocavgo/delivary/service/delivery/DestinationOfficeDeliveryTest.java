package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the destination-office delivery leg: a driver drops a FIXED_ROUTE
 * package at the office (DESTINATION_OFFICE, custody role OFFICE) and office
 * staff then run the whole delivery — visibility (myPackages), initiateDelivery
 * and confirmDelivery all work without being the recorded custodian user, and
 * DELIVERED is terminal (no COMPLETED step).
 */
@SpringBootTest
@Transactional
class DestinationOfficeDeliveryTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserJpaRepository userRepo;

    private long nextUserId = 4_000_000L;

    private void authenticateAs(Long userId, Role role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
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

    private UUID createFixedRoutePackage(Long customerId) {
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        return packageService.createPackage(customerId, Role.CUSTOMER, null, input).deliveryPackage().id();
    }

    @Test
    void officeDeliversPackageAfterDriverHandoff() {
        var customerId = createUser("dst-customer@test.com", Role.CUSTOMER);
        var driverId = createUser("dst-driver@test.com", Role.DRIVER);
        var officeWorkerId = createUser("dst-worker@test.com", Role.WORKER);

        // 1. Customer creates FIXED_ROUTE package with AUTO transfer → CREATED
        var pkgId = createFixedRoutePackage(customerId);
        var transferId = transferService.getTransfersByCreator(customerId).get(0).id();

        // 2. Driver accepts from the sender directly → PICKED_UP (driver holds it)
        authenticateAs(driverId, Role.DRIVER);
        var accepted = packageService.acceptPackageByTransfer(driverId, transferId, null)
                .acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.PICKED_UP);

        // 3. Driver goes in transit, then drops the package at the destination office
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.IN_TRANSIT, null));
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.DESTINATION_OFFICE, null));

        // The package is now held under an OFFICE custody row (user = the driver)
        var pkg = packageService.getPackageById(pkgId);
        var current = pkg.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
        assertThat(current.role()).isEqualTo(CustodianRole.OFFICE);
        assertThat(current.userId()).isEqualTo(driverId);

        // 4. The destination office worker can SEE the office-held package
        var workerPage = packageService.getMyPackages(officeWorkerId, null, Role.WORKER);
        assertThat(workerPage.items()).extracting(d -> d.id()).contains(pkgId);

        // 5. The office runs the delivery leg: initiate (staff bypass, valid from DESTINATION_OFFICE)
        authenticateAs(officeWorkerId, Role.WORKER);
        var initiated = packageService.initiateDelivery(officeWorkerId, pkgId);
        assertThat(initiated.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);
        assertThat(initiated.deliveryCode()).isNotBlank();

        // 6. The office confirms with the code presented by the (walk-in) receiver → DELIVERED, terminal
        var confirmed = packageService.confirmDelivery(officeWorkerId, pkgId, initiated.deliveryCode());
        assertThat(confirmed.status()).isEqualTo(PackageStatus.DELIVERED);

        // 7. No COMPLETED step — DELIVERED is the end of the line
        authenticateAs(officeWorkerId, Role.WORKER);
        assertThatThrownBy(() -> packageService.updateStatus(
                new UpdatePackageStatusInput(pkgId, officeWorkerId, PackageStatus.COMPLETED, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid status transition: DELIVERED -> COMPLETED");
    }

    @Test
    void driverAcceptsFromSenderAndDeliversStraightFromPickedUp() {
        // Sender → driver direct (FIXED_ROUTE): after accepting from the sender the driver
        // holds the package at PICKED_UP and can run the whole delivery leg immediately —
        // no office step in between (per the mobile driver card, which offers Deliver
        // right at PICKED_UP).
        var customerId = createUser("sdd-customer@test.com", Role.CUSTOMER);
        var driverId = createUser("sdd-driver@test.com", Role.DRIVER);

        var pkgId = createFixedRoutePackage(customerId);
        var transferId = transferService.getTransfersByCreator(customerId).get(0).id();

        // Driver accepts the sender's offer → PICKED_UP, driver custody
        authenticateAs(driverId, Role.DRIVER);
        var accepted = packageService.acceptPackageByTransfer(driverId, transferId, null)
                .acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.PICKED_UP);

        // Deliver straight from PICKED_UP (no IN_TRANSIT / office leg needed)
        var initiated = packageService.initiateDelivery(driverId, pkgId);
        assertThat(initiated.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);
        assertThat(initiated.deliveryCode()).isNotBlank();

        // Confirm with the code → DELIVERED (terminal)
        var confirmed = packageService.confirmDelivery(driverId, pkgId, initiated.deliveryCode());
        assertThat(confirmed.status()).isEqualTo(PackageStatus.DELIVERED);

        // The receiver's view (myPackages for the sender) shows it delivered
        var customerPage = packageService.getMyPackages(customerId, null, Role.CUSTOMER);
        assertThat(customerPage.items()).extracting(d -> d.id()).contains(pkgId);
    }

    @Test
    void driverTransferToOfficeAccept_landsAtDestinationOffice() {
        // Driver holds an in-flight FIXED_ROUTE package and transfers it to the office;
        // when the office (WORKER) accepts the transfer, the package auto-advances to
        // DESTINATION_OFFICE under an OFFICE custody row — not left at IN_TRANSIT.
        var customerId = createUser("d2o-customer@test.com", Role.CUSTOMER);
        var driverId = createUser("d2o-driver@test.com", Role.DRIVER);
        var officeWorkerId = createUser("d2o-worker@test.com", Role.WORKER);

        var pkgId = createFixedRoutePackage(customerId);
        var transferId = transferService.getTransfersByCreator(customerId).get(0).id();

        // Driver accepts from the sender → PICKED_UP, then goes in transit
        authenticateAs(driverId, Role.DRIVER);
        packageService.acceptPackageByTransfer(driverId, transferId, null);
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.IN_TRANSIT, null));

        // Driver creates an AUTO transfer to the office
        authenticateAs(driverId, Role.DRIVER);
        var toOffice = transferService.createTransfer(driverId,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        // Office worker accepts → package lands at DESTINATION_OFFICE with OFFICE custody
        authenticateAs(officeWorkerId, Role.WORKER);
        var accepted = packageService.acceptPackageByTransfer(officeWorkerId, toOffice.id(), null)
                .acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.DESTINATION_OFFICE);
        var current = accepted.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
        assertThat(current.role()).isEqualTo(CustodianRole.OFFICE);
        assertThat(current.userId()).isEqualTo(officeWorkerId);

        // The office then runs the delivery leg straight from DESTINATION_OFFICE
        var initiated = packageService.initiateDelivery(officeWorkerId, pkgId);
        assertThat(initiated.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);
        var confirmed = packageService.confirmDelivery(officeWorkerId, pkgId, initiated.deliveryCode());
        assertThat(confirmed.status()).isEqualTo(PackageStatus.DELIVERED);
    }

    @Test
    void driverTransferToDriver_keepsInFlightStatus() {
        // Driver-to-driver handoff is NOT an office handoff — the package stays in
        // flight (IN_TRANSIT) and merely swaps custody to the accepting driver.
        var customerId = createUser("d2d-customer@test.com", Role.CUSTOMER);
        var driver1Id = createUser("d2d-driver1@test.com", Role.DRIVER);
        var driver2Id = createUser("d2d-driver2@test.com", Role.DRIVER);

        var pkgId = createFixedRoutePackage(customerId);
        var transferId = transferService.getTransfersByCreator(customerId).get(0).id();

        authenticateAs(driver1Id, Role.DRIVER);
        packageService.acceptPackageByTransfer(driver1Id, transferId, null);
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driver1Id, PackageStatus.IN_TRANSIT, null));

        // Driver 1 hands off to Driver 2 (AUTO transfer accept by another DRIVER)
        authenticateAs(driver1Id, Role.DRIVER);
        var handoff = transferService.createTransfer(driver1Id,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));
        authenticateAs(driver2Id, Role.DRIVER);
        var accepted = packageService.acceptPackageByTransfer(driver2Id, handoff.id(), null)
                .acceptedPackages().get(0).deliveryPackage();

        assertThat(accepted.status()).isEqualTo(PackageStatus.IN_TRANSIT);
        var current = accepted.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
        assertThat(current.role()).isEqualTo(CustodianRole.DRIVER);
        assertThat(current.userId()).isEqualTo(driver2Id);
    }

    @Test
    void driverStillNeedsCustodyToInitiate() {
        var customerId = createUser("dst-customer2@test.com", Role.CUSTOMER);
        var driverId = createUser("dst-driver2@test.com", Role.DRIVER);
        var otherDriverId = createUser("dst-driver3@test.com", Role.DRIVER);

        var pkgId = createFixedRoutePackage(customerId);
        var transferId = transferService.getTransfersByCreator(customerId).get(0).id();

        authenticateAs(driverId, Role.DRIVER);
        packageService.acceptPackageByTransfer(driverId, transferId, null);
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.IN_TRANSIT, null));

        // A non-custodian DRIVER (not office staff) cannot initiate delivery
        authenticateAs(otherDriverId, Role.DRIVER);
        assertThatThrownBy(() -> packageService.initiateDelivery(otherDriverId, pkgId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("current custodian or office staff");
    }
}
