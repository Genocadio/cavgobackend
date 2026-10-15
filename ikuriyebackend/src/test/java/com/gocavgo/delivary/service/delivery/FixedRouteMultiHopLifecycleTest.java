package com.gocavgo.delivary.service.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.delivery.*;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.enums.user.*;
import com.gocavgo.delivary.repository.notification.NoticeRepository;
import com.gocavgo.delivary.repository.notification.NoticeViewerRepository;
import com.gocavgo.delivary.repository.user.DriverProfileJpaRepository;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.service.transfer.TransferService;
import org.junit.jupiter.api.DisplayName;
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

/**
 * End-to-end integration test verifying the complete multi-hop package lifecycles
 * in FIXED_ROUTE mode across Senders, Drivers, Offices, and Receivers.
 */
@SpringBootTest
@Transactional
class FixedRouteMultiHopLifecycleTest {

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
    private NoticeViewerRepository noticeViewerRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private long nextUserId = 5_000_000L;

    // ── Helpers ────────────────────────────────────────────────────────────

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

    private PackageResponse.CustodianResponse lastCustodian(PackageResponse pkg) {
        assertThat(pkg.custodians()).isNotEmpty();
        return pkg.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
    }

    private String recipientDeliveryCode(Long recipientUserId) {
        return noticeViewerRepo.findByUserIdOrderByCreatedAtDesc(recipientUserId).stream()
                .map(v -> noticeRepo.findById(v.getNoticeId()).orElse(null))
                .filter(n -> n != null && n.getEventType() == NoticeEventType.PACKAGE_DELIVERY_INITIATED)
                .findFirst()
                .map(com.gocavgo.delivary.entity.notification.NoticeEntity::getPayload)
                .map(payload -> {
                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        return node.get("deliveryCode") != null ? node.get("deliveryCode").asText() : null;
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElseThrow(() -> new AssertionError("No delivery code notice found for recipient " + recipientUserId));
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Flow 1: Sender -> Driver 1 -> Driver 2 -> Office -> Deliver to Receiver")
    void testFlow1_SenderToDriver1ToDriver2ToOfficeToReceiver() {
        var senderId = createUser("sender1@test.com", Role.CUSTOMER);
        var receiverId = createUser("receiver1@test.com", Role.CUSTOMER);
        var driver1Id = createUser("driver1@test.com", Role.DRIVER);
        var driver2Id = createUser("driver2@test.com", Role.DRIVER);
        var workerId = createUser("worker1@test.com", Role.WORKER);
        makeOnlineDriver(driver1Id);
        makeOnlineDriver(driver2Id);

        // 1. Sender creates a FIXED_ROUTE package with AUTO transfer
        authenticateAs(senderId, Role.CUSTOMER);
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, senderId, "Alice", "+250780000001"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, receiverId, "Bob", "+250780000002"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, -1.94, 30.05, "Kigali Station", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, -1.67, 29.23, "Gisenyi Office", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(senderId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();
        var initialTransferId = creation.transfer().id();

        var initial = packageService.getPackageById(pkgId);
        assertThat(initial.status()).isEqualTo(PackageStatus.CREATED);

        // 2. Driver 1 accepts the package from sender -> PICKED_UP
        authenticateAs(driver1Id, Role.DRIVER);
        var accept1 = packageService.acceptPackageByTransfer(driver1Id, initialTransferId, null);
        var pkgAfterD1 = accept1.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAfterD1.status()).isEqualTo(PackageStatus.PICKED_UP);
        assertThat(lastCustodian(pkgAfterD1).userId()).isEqualTo(driver1Id);

        // Driver 1 moves to IN_TRANSIT
        var inTransitD1 = packageService.updateStatus(
                new UpdatePackageStatusInput(pkgId, driver1Id, PackageStatus.IN_TRANSIT, "Heading towards transfer point"));
        assertThat(inTransitD1.status()).isEqualTo(PackageStatus.IN_TRANSIT);

        // 3. Driver 1 transfers to Driver 2 mid-route (relay)
        var relayTransfer = transferService.createTransfer(driver1Id,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));
        assertThat(relayTransfer.status()).isEqualTo(TransferStatus.PENDING);

        authenticateAs(driver2Id, Role.DRIVER);
        var accept2 = packageService.acceptPackageByTransfer(driver2Id, relayTransfer.id(), null);
        var pkgAfterD2 = accept2.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAfterD2.status()).isEqualTo(PackageStatus.IN_TRANSIT);
        assertThat(lastCustodian(pkgAfterD2).userId()).isEqualTo(driver2Id);
        assertThat(lastCustodian(pkgAfterD2).role()).isEqualTo(CustodianRole.DRIVER);

        // 4. Driver 2 transfers to Destination Office
        var officeTransfer = transferService.createTransfer(driver2Id,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        authenticateAs(workerId, Role.WORKER);
        var acceptOffice = packageService.acceptPackageByTransfer(workerId, officeTransfer.id(), null);
        var pkgAtOffice = acceptOffice.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAtOffice.status()).isEqualTo(PackageStatus.DESTINATION_OFFICE);
        assertThat(lastCustodian(pkgAtOffice).role()).isEqualTo(CustodianRole.OFFICE);

        // 5. Office initiates delivery to Receiver
        var deliveryResult = packageService.initiateDelivery(workerId, pkgId);
        assertThat(deliveryResult.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);

        // 6. Receiver presents delivery code, confirmed to DELIVERED
        var code = recipientDeliveryCode(receiverId);
        assertThat(code).isNotNull().hasSize(6);

        var finalPkg = packageService.confirmDelivery(receiverId, pkgId, code);
        assertThat(finalPkg.status()).isEqualTo(PackageStatus.DELIVERED);

        // Verify custody audit trail
        var custodyHistory = packageService.getPackageCustodyHistory(pkgId);
        assertThat(custodyHistory).isNotEmpty();
    }

    @Test
    @DisplayName("Flow 2: Sender -> Office 1 -> Driver -> Office 2 -> Receiver")
    void testFlow2_SenderToOffice1ToDriverToOffice2ToReceiver() {
        var senderId = createUser("sender2@test.com", Role.CUSTOMER);
        var receiverId = createUser("receiver2@test.com", Role.CUSTOMER);
        var office1WorkerId = createUser("office1@test.com", Role.WORKER);
        var office2WorkerId = createUser("office2@test.com", Role.WORKER);
        var driverId = createUser("driverF2@test.com", Role.DRIVER);
        makeOnlineDriver(driverId);

        // 1. Sender drops off package at Office 1
        authenticateAs(senderId, Role.CUSTOMER);
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, senderId, "Sender 2", "+250780000003"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, receiverId, "Receiver 2", "+250780000004"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, -1.94, 30.05, "Office 1 Kigali", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, -1.50, 29.63, "Office 2 Musanze", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(senderId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();
        var initialTransferId = creation.transfer().id();

        // Office 1 Worker accepts package -> ORIGIN_OFFICE
        authenticateAs(office1WorkerId, Role.WORKER);
        var acceptO1 = packageService.acceptPackageByTransfer(office1WorkerId, initialTransferId, null);
        var pkgAtO1 = acceptO1.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAtO1.status()).isEqualTo(PackageStatus.ORIGIN_OFFICE);

        // 2. Office 1 assigns Driver or creates transfer to Driver
        var driverTransfer = transferService.createTransfer(office1WorkerId,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        authenticateAs(driverId, Role.DRIVER);
        var acceptDriver = packageService.acceptPackageByTransfer(driverId, driverTransfer.id(), null);
        var pkgWithDriver = acceptDriver.acceptedPackages().get(0).deliveryPackage();
        assertThat(lastCustodian(pkgWithDriver).userId()).isEqualTo(driverId);

        // Driver departs from Office 1 towards Office 2 -> IN_TRANSIT
        var inTransit = packageService.updateStatus(
                new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.IN_TRANSIT, "Departed Kigali Office"));
        assertThat(inTransit.status()).isEqualTo(PackageStatus.IN_TRANSIT);

        // 3. Driver arrives at Office 2 and transfers to Office 2
        var dropTransfer = transferService.createTransfer(driverId,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        authenticateAs(office2WorkerId, Role.WORKER);
        var acceptO2 = packageService.acceptPackageByTransfer(office2WorkerId, dropTransfer.id(), null);
        var pkgAtO2 = acceptO2.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAtO2.status()).isEqualTo(PackageStatus.DESTINATION_OFFICE);
        assertThat(lastCustodian(pkgAtO2).role()).isEqualTo(CustodianRole.OFFICE);

        // 4. Office 2 marks READY_FOR_COLLECTION
        var readyPkg = packageService.updateStatus(
                new UpdatePackageStatusInput(pkgId, office2WorkerId, PackageStatus.READY_FOR_COLLECTION, "Ready at Musanze branch"));
        assertThat(readyPkg.status()).isEqualTo(PackageStatus.READY_FOR_COLLECTION);

        // 5. Office 2 initiates delivery when receiver arrives
        var deliveryResult = packageService.initiateDelivery(office2WorkerId, pkgId);
        assertThat(deliveryResult.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);

        // 6. Confirmed with delivery code
        var code = recipientDeliveryCode(receiverId);
        var deliveredPkg = packageService.confirmDelivery(office2WorkerId, pkgId, code);
        assertThat(deliveredPkg.status()).isEqualTo(PackageStatus.DELIVERED);
    }

    @Test
    @DisplayName("Flow 3: Driver creates package for Sender -> to Office 1 -> to Office 2 -> to Receiver")
    void testFlow3_DriverCreatesForSenderToOffice1ToOffice2ToReceiver() {
        var senderId = createUser("sender3@test.com", Role.CUSTOMER);
        var receiverId = createUser("receiver3@test.com", Role.CUSTOMER);
        var driverId = createUser("driverF3@test.com", Role.DRIVER);
        var hubWorkerId = createUser("hubWorker@test.com", Role.WORKER);
        var branchWorkerId = createUser("branchWorker@test.com", Role.WORKER);
        makeOnlineDriver(driverId);

        // 1. Driver creates package in the field for a Sender -> initial status PICKED_UP
        authenticateAs(driverId, Role.DRIVER);
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, senderId, "Sender 3", "+250780000005"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, receiverId, "Receiver 3", "+250780000006"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, -1.94, 30.05, "Field Pickup Point", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, -2.60, 29.74, "Butare Office", null, null),
                null,
                null, // created directly by driver
                null,
                null
        );
        var creation = packageService.createPackage(driverId, Role.DRIVER, null, input);
        var pkgId = creation.deliveryPackage().id();
        var initialPkg = packageService.getPackageById(pkgId);

        // When driver creates, driver is first custodian and initial status is ACCEPTED
        assertThat(initialPkg.status()).isEqualTo(PackageStatus.ACCEPTED);
        assertThat(lastCustodian(initialPkg).userId()).isEqualTo(driverId);

        // Driver heads to Hub -> IN_TRANSIT
        packageService.updateStatus(new UpdatePackageStatusInput(pkgId, driverId, PackageStatus.IN_TRANSIT, "Heading to Hub"));

        // 2. Driver drops package at Hub (Office 1)
        var hubTransfer = transferService.createTransfer(driverId,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        authenticateAs(hubWorkerId, Role.WORKER);
        var acceptHub = packageService.acceptPackageByTransfer(hubWorkerId, hubTransfer.id(), null);
        var pkgAtHub = acceptHub.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAtHub.status()).isEqualTo(PackageStatus.DESTINATION_OFFICE);
        assertThat(lastCustodian(pkgAtHub).role()).isEqualTo(CustodianRole.OFFICE);

        // 3. Hub transfers to Branch (Office 2)
        var branchTransfer = transferService.createTransfer(hubWorkerId,
                new CreateTransferInput(List.of(pkgId), TransferRuleType.AUTO, null, null, null));

        authenticateAs(branchWorkerId, Role.WORKER);
        var acceptBranch = packageService.acceptPackageByTransfer(branchWorkerId, branchTransfer.id(), null);
        var pkgAtBranch = acceptBranch.acceptedPackages().get(0).deliveryPackage();
        assertThat(pkgAtBranch.status()).isEqualTo(PackageStatus.DESTINATION_OFFICE);
        assertThat(lastCustodian(pkgAtBranch).role()).isEqualTo(CustodianRole.OFFICE);

        // 4. Branch delivers to Receiver
        packageService.initiateDelivery(branchWorkerId, pkgId);
        var code = recipientDeliveryCode(receiverId);
        var completed = packageService.confirmDelivery(receiverId, pkgId, code);
        assertThat(completed.status()).isEqualTo(PackageStatus.DELIVERED);
    }

    @Test
    @DisplayName("Flow 4: Driver direct door-to-door delivery on FIXED_ROUTE")
    void testFlow4_DriverDirectDeliveryToReceiver_FixedRoute() {
        var senderId = createUser("sender4@test.com", Role.CUSTOMER);
        var receiverId = createUser("receiver4@test.com", Role.CUSTOMER);
        var driverId = createUser("driverF4@test.com", Role.DRIVER);
        makeOnlineDriver(driverId);

        // 1. Sender creates package with AUTO transfer
        authenticateAs(senderId, Role.CUSTOMER);
        var input = new CreatePackageInput(
                DeliveryType.FIXED_ROUTE,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, senderId, "Alice", "+250780000007"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, receiverId, "Bob", "+250780000008"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, -1.94, 30.05, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, -1.95, 30.06, "Destination", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(senderId, Role.CUSTOMER, null, input);
        var pkgId = creation.deliveryPackage().id();

        // 2. Driver accepts from sender -> PICKED_UP
        authenticateAs(driverId, Role.DRIVER);
        packageService.acceptPackageByTransfer(driverId, creation.transfer().id(), null);

        // Driver directly initiates delivery to receiver
        var deliveryResult = packageService.initiateDelivery(driverId, pkgId);
        assertThat(deliveryResult.deliveryPackage().status()).isEqualTo(PackageStatus.PENDING_CONFIRMATION);

        // 3. Driver confirms delivery with code given by receiver
        var code = recipientDeliveryCode(receiverId);
        var delivered = packageService.confirmDelivery(driverId, pkgId, code);
        assertThat(delivered.status()).isEqualTo(PackageStatus.DELIVERED);
    }
}
