package com.gocavgo.delivary;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.DriverType;
import com.gocavgo.delivary.enums.user.EmploymentType;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.repository.notification.NoticeRepository;
import com.gocavgo.delivary.repository.notification.NoticeViewerRepository;
import com.gocavgo.delivary.repository.user.DriverProfileJpaRepository;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.delivery.PackageService;
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
 * Covers the mid-route transfer handoff: a package already IN_TRANSIT can be
 * handed to another driver via acceptTransfer — the package KEEPS its status
 * (no regression back to ACCEPTED) and the custodian swaps to the new driver.
 *
 * Also verifies the identity-based custody guards that replaced the handover
 * token: non-custodians cannot advance a package, and terminal packages cannot
 * be handed off via a transfer.
 */
@SpringBootTest
@Transactional
class MidRouteTransferTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserJpaRepository userRepo;

    @Autowired
    private DriverProfileJpaRepository driverProfileRepo;

    @Autowired
    private NoticeRepository noticeRepo;

    @Autowired
    private NoticeViewerRepository viewerRepo;

    // ── Helpers ────────────────────────────────────────────────────────────

    private long nextUserId = 1_000_000L;

    /** Sets up SecurityContext with the given role for the given userId. */
    private void authenticateAs(Long userId, Role role) {
        var authorities = java.util.List.of(
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

    private void makeOnlineDriver(Long userId) {
        var profile = DriverProfileEntity.builder()
                .user(userRepo.findById(userId).orElseThrow())
                .employmentType(EmploymentType.INDEPENDENT)
                .driverType(DriverType.OPEN)
                .status(DriverStatus.ONLINE)
                .createdAt(Instant.now())
                .build();
        driverProfileRepo.save(profile);
    }

    private PackageResponse createOpenPackage(Long customerId, TransferRuleType ruleType) {
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
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

    // ── The mid-route handoff ──────────────────────────────────────────────

    @Test
    void midRouteTransfer_keepsStatus_andSwapsCustodian() {
        var customerId = createUser("alice@test.com", Role.CUSTOMER);
        var driver1Id = createUser("driver1@test.com", Role.DRIVER);
        var driver2Id = createUser("driver2@test.com", Role.DRIVER);
        makeOnlineDriver(driver1Id);
        makeOnlineDriver(driver2Id);

        // 1. Customer creates an OPEN package with an AUTO transfer → CREATED
        var created = createOpenPackage(customerId, TransferRuleType.AUTO);
        assertThat(created.status()).isEqualTo(PackageStatus.CREATED);
        assertThat(created.custodians()).isEmpty();
        assertThat(created.transfers()).hasSize(1);
        var firstTransferId = created.transfers().get(0).id();

        // 2. Driver 1 accepts the original transfer → ACCEPTED, custodian = driver 1
        authenticateAs(driver1Id, Role.DRIVER);
        var firstAccept = packageService.acceptPackageByTransfer(driver1Id, firstTransferId, null);
        var accepted = firstAccept.acceptedPackages().get(0).deliveryPackage();
        assertThat(accepted.status()).isEqualTo(PackageStatus.ACCEPTED);
        assertThat(lastCustodian(accepted).userId()).isEqualTo(driver1Id);

        // 3. Driver 1 picks up and goes in transit (identity proves custody — no token)
        authenticateAs(driver1Id, Role.DRIVER);
        packageService.updateStatus(new UpdatePackageStatusInput(accepted.id(), driver1Id, PackageStatus.PICKED_UP, null));
        var inTransit = packageService.updateStatus(
                new UpdatePackageStatusInput(accepted.id(), driver1Id, PackageStatus.IN_TRANSIT, null));
        assertThat(inTransit.status()).isEqualTo(PackageStatus.IN_TRANSIT);
        assertThat(lastCustodian(inTransit).userId()).isEqualTo(driver1Id);

        // 4. Driver 1 creates a NEW transfer for the in-flight package
        var midRouteTransfer = transferService.createTransfer(driver1Id,
                new CreateTransferInput(List.of(accepted.id()), TransferRuleType.AUTO, null, null, null));

        // 5. Driver 2 accepts → status STAYS IN_TRANSIT, custodian becomes driver 2
        authenticateAs(driver2Id, Role.DRIVER);
        var handoff = packageService.acceptPackageByTransfer(driver2Id, midRouteTransfer.id(), null);
        var handedOver = handoff.acceptedPackages().get(0).deliveryPackage();

        assertThat(handedOver.status()).isEqualTo(PackageStatus.IN_TRANSIT);
        // Both drivers hold custodian rows; only driver 2 is the CURRENT custodian.
        assertThat(handedOver.custodians())
                .extracting(PackageResponse.CustodianResponse::userId)
                .contains(driver1Id, driver2Id);
        assertThat(lastCustodian(handedOver).userId()).isEqualTo(driver2Id);
        assertThat(lastCustodian(handedOver).role()).isEqualTo(CustodianRole.DRIVER);

        // The transfer that carried the handoff is now DONE
        assertThat(handoff.transfer().status()).isEqualTo(com.gocavgo.delivary.enums.transfer.TransferStatus.DONE);

        // 6. Custody chain records the DRIVER → DRIVER handoff
        assertThat(handedOver.custody())
                .anyMatch(c -> "DRIVER".equals(c.fromEntity()) && "DRIVER".equals(c.toEntity()));

        // The replaced custodian (driver 1) receives a PACKAGE_CUSTODIAN_REMOVED notice.
        // This is the exact notice path that used to NPE (Map.of with null previousStatus).
        assertThat(custodianRemovedNoticesFor(handedOver.id(), driver1Id)).isTrue();

        // 7. The new custodian can continue the delivery leg
        authenticateAs(driver2Id, Role.DRIVER);
        var delivered = packageService.initiateDelivery(driver2Id, handedOver.id());
        assertThat(delivered.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);
    }

    // ── Negative: non-custodian cannot advance status ──────────────────────

    @Test
    void nonCustodianDriver_cannotAdvanceStatus() {
        var customerId = createUser("carol@test.com", Role.CUSTOMER);
        var driver1Id = createUser("driver3@test.com", Role.DRIVER);
        var driver2Id = createUser("driver4@test.com", Role.DRIVER);
        makeOnlineDriver(driver1Id);
        makeOnlineDriver(driver2Id);

        var created = createOpenPackage(customerId, TransferRuleType.AUTO);
        var transferId = created.transfers().get(0).id();

        // Driver 1 accepts → custodian = driver 1
        authenticateAs(driver1Id, Role.DRIVER);
        packageService.acceptPackageByTransfer(driver1Id, transferId, null);

        // Driver 2 (NOT the custodian) tries to advance the package → rejected
        assertThatThrownBy(() -> packageService.updateStatus(
                new UpdatePackageStatusInput(created.id(), driver2Id, PackageStatus.PICKED_UP, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("current custodian");
    }

    // ── Negative: terminal packages cannot be handed off via a transfer ────

    @Test
    void cancelledPackage_cannotBeHandedOffViaTransfer() {
        var customerId = createUser("dave@test.com", Role.CUSTOMER);
        var driver1Id = createUser("driver5@test.com", Role.DRIVER);
        var driver2Id = createUser("driver6@test.com", Role.DRIVER);
        makeOnlineDriver(driver1Id);
        makeOnlineDriver(driver2Id);

        var created = createOpenPackage(customerId, TransferRuleType.AUTO);
        var transferId = created.transfers().get(0).id();
        authenticateAs(driver1Id, Role.DRIVER);
        packageService.acceptPackageByTransfer(driver1Id, transferId, null);

        // Cancel the package (driver 1 is the current custodian → allowed)
        authenticateAs(driver1Id, Role.DRIVER);
        packageService.updateStatus(
                new UpdatePackageStatusInput(created.id(), driver1Id, PackageStatus.CANCELLED, null));
        assertThat(packageService.getPackageById(created.id()).status()).isEqualTo(PackageStatus.CANCELLED);

        // A new transfer can technically be created, but accepting it must fail
        var doomedTransfer = transferService.createTransfer(driver1Id,
                new CreateTransferInput(List.of(created.id()), TransferRuleType.AUTO, null, null, null));

        authenticateAs(driver2Id, Role.DRIVER);
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(driver2Id, doomedTransfer.id(), null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Cannot accept a package with status");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Resolves the CURRENT custodian deterministically — the row with the most
     * recent assignedAt — rather than relying on list position (findByPackageId
     * is not order-guaranteed). Mirrors how the backend itself resolves custody.
     */
    private PackageResponse.CustodianResponse lastCustodian(PackageResponse pkg) {
        assertThat(pkg.custodians()).isNotEmpty();
        return pkg.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
    }

    private boolean custodianRemovedNoticesFor(UUID packageId, Long userId) {
        var notice = noticeRepo.findAll().stream()
                .filter(n -> n.getResourceId().equals(packageId))
                .filter(n -> n.getEventType() == NoticeEventType.PACKAGE_CUSTODIAN_REMOVED)
                .findFirst();
        return notice
                .map(n -> viewerRepo.findByNoticeId(n.getId()).stream()
                        .anyMatch(v -> v.getUserId().equals(userId)))
                .orElse(false);
    }
}
