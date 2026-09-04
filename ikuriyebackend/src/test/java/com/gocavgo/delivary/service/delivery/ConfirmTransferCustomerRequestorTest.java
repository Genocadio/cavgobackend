package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.DriverType;
import com.gocavgo.delivary.enums.user.EmploymentType;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.repository.user.DriverProfileJpaRepository;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.repository.user.WorkerProfileJpaRepository;
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
 * Regression tests for the CONFIRM transfer flow.
 *
 * <p>Bug: {@code acceptPackagesForTransferConfirmation} called
 * {@code SecurityUtils.getCurrentUserRole()} which returned the confirming
 * <em>owner's</em> role, not the requestor's role. This caused
 * {@code RuntimeException: User role cannot accept packages: CUSTOMER}
 * when the owner was a CUSTOMER.
 *
 * <p>Fix: the method now resolves the requestor's custodian role from their
 * profile (driver → DRIVER, worker → WORKER, otherwise → RECEIVER).
 */
@SpringBootTest
@Transactional
class ConfirmTransferCustomerRequestorTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserJpaRepository userRepo;

    @Autowired
    private WorkerProfileJpaRepository workerProfileRepo;

    @Autowired
    private DriverProfileJpaRepository driverProfileRepo;

    private long nextUserId = 3_000_000L;

    // ── Helpers ────────────────────────────────────────────────────────────

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

    private void createWorkerProfile(Long userId) {
        var user = userRepo.findById(userId).orElseThrow();
        workerProfileRepo.save(WorkerProfileEntity.builder()
                .user(user)
                .createdAt(Instant.now())
                .build());
    }

    private void createDriverProfile(Long userId) {
        var user = userRepo.findById(userId).orElseThrow();
        driverProfileRepo.save(DriverProfileEntity.builder()
                .user(user)
                .employmentType(EmploymentType.INDEPENDENT)
                .driverType(DriverType.OPEN)
                .status(DriverStatus.ONLINE)
                .createdAt(Instant.now())
                .build());
    }

    private PackageResponse createOpenPackage(Long customerId) {
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                TransferRuleType.CONFIRM,
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

    // ── Core regression: owner is CUSTOMER, requestor is WORKER ────────────

    @Test
    void confirmTransfer_workerRequestor_ownerIsCustomer() {
        var ownerCustomerId = createUser("owner-c2@test.com", Role.CUSTOMER);
        var workerRequestorId = createUser("worker-req@test.com", Role.WORKER);
        createWorkerProfile(workerRequestorId);

        // 1. Customer creates an OPEN package with a CONFIRM transfer
        var created = createOpenPackage(ownerCustomerId);
        assertThat(created.status()).isEqualTo(PackageStatus.CREATED);
        var transferId = created.transfers().get(0).id();

        // 2. Worker requests the transfer → status = REQUESTED
        authenticateAs(workerRequestorId, Role.WORKER);
        var requestResult = transferService.acceptTransfer(workerRequestorId, transferId, null);
        assertThat(requestResult.transfer().status()).isEqualTo(TransferStatus.REQUESTED);

        // 3. Customer (owner) confirms the transfer
        //    Before the fix: FAILS with "User role cannot accept packages: CUSTOMER"
        //    After the fix: succeeds, worker gets packages as WORKER custodian.
        authenticateAs(ownerCustomerId, Role.CUSTOMER);
        var confirmedTransfer = transferService.confirmTransfer(ownerCustomerId, transferId);
        assertThat(confirmedTransfer.status()).isEqualTo(TransferStatus.DONE);

        // 4. Verify the package was accepted by the worker
        var pkg = packageService.getPackageById(created.id());
        assertThat(pkg.status()).isIn(PackageStatus.ACCEPTED, PackageStatus.ORIGIN_OFFICE);
        assertThat(lastCustodian(pkg).userId()).isEqualTo(workerRequestorId);
        assertThat(lastCustodian(pkg).role()).isEqualTo(CustodianRole.WORKER);
    }

    // ── Owner is CUSTOMER, requestor is DRIVER ─────────────────────────────

    @Test
    void confirmTransfer_driverRequestor_ownerIsCustomer() {
        var ownerCustomerId = createUser("owner-c3@test.com", Role.CUSTOMER);
        var driverRequestorId = createUser("driver-req@test.com", Role.DRIVER);
        createDriverProfile(driverRequestorId);

        // 1. Customer creates an OPEN package with a CONFIRM transfer
        var created = createOpenPackage(ownerCustomerId);
        var transferId = created.transfers().get(0).id();

        // 2. Driver requests the transfer → status = REQUESTED
        authenticateAs(driverRequestorId, Role.DRIVER);
        var requestResult = transferService.acceptTransfer(driverRequestorId, transferId, null);
        assertThat(requestResult.transfer().status()).isEqualTo(TransferStatus.REQUESTED);

        // 3. Customer (owner) confirms → driver gets packages as DRIVER custodian
        authenticateAs(ownerCustomerId, Role.CUSTOMER);
        var confirmedTransfer = transferService.confirmTransfer(ownerCustomerId, transferId);
        assertThat(confirmedTransfer.status()).isEqualTo(TransferStatus.DONE);

        var pkg = packageService.getPackageById(created.id());
        // Driver acceptor from sender directly → PICKED_UP (not ACCEPTED)
        assertThat(pkg.status()).isEqualTo(PackageStatus.PICKED_UP);
        assertThat(lastCustodian(pkg).userId()).isEqualTo(driverRequestorId);
        assertThat(lastCustodian(pkg).role()).isEqualTo(CustodianRole.DRIVER);
    }

    // ── Owner is CUSTOMER, requestor is WORKER without profile → RECEIVER ──

    @Test
    void confirmTransfer_workerWithoutProfile_getsReceiverRole() {
        var ownerCustomerId = createUser("owner-c7@test.com", Role.CUSTOMER);
        var workerRequestorId = createUser("worker-no-profile@test.com", Role.WORKER);
        // No worker profile created → resolveRequestorCustodianRole falls back to RECEIVER

        var created = createOpenPackage(ownerCustomerId);
        var transferId = created.transfers().get(0).id();

        authenticateAs(workerRequestorId, Role.WORKER);
        transferService.acceptTransfer(workerRequestorId, transferId, null);

        authenticateAs(ownerCustomerId, Role.CUSTOMER);
        transferService.confirmTransfer(ownerCustomerId, transferId);

        var pkg = packageService.getPackageById(created.id());
        assertThat(lastCustodian(pkg).userId()).isEqualTo(workerRequestorId);
        // No profile → RECEIVER custodian role
        assertThat(lastCustodian(pkg).role()).isEqualTo(CustodianRole.RECEIVER);
    }

    // ── Non-owner cannot confirm ───────────────────────────────────────────

    @Test
    void confirmTransfer_onlyOwnerCanConfirm() {
        var ownerCustomerId = createUser("owner-c5@test.com", Role.CUSTOMER);
        var workerRequestorId = createUser("worker-req2@test.com", Role.WORKER);
        createWorkerProfile(workerRequestorId);
        var otherWorkerId = createUser("worker-other2@test.com", Role.WORKER);

        var created = createOpenPackage(ownerCustomerId);
        var transferId = created.transfers().get(0).id();

        // Worker requests
        authenticateAs(workerRequestorId, Role.WORKER);
        transferService.acceptTransfer(workerRequestorId, transferId, null);

        // Another worker (not the owner) tries to confirm → should fail
        authenticateAs(otherWorkerId, Role.WORKER);
        assertThatThrownBy(() -> transferService.confirmTransfer(otherWorkerId, transferId))
                .isInstanceOf(com.gocavgo.delivary.exception.BusinessValidationException.class)
                .hasMessageContaining("Only the transfer owner");
    }

    // ── Reject returns transfer to PENDING ─────────────────────────────────

    @Test
    void confirmTransfer_rejectReturnsTransferToOpen() {
        var ownerCustomerId = createUser("owner-c6@test.com", Role.CUSTOMER);
        var workerRequestorId = createUser("worker-req3@test.com", Role.WORKER);
        createWorkerProfile(workerRequestorId);

        var created = createOpenPackage(ownerCustomerId);
        var transferId = created.transfers().get(0).id();

        // Worker requests
        authenticateAs(workerRequestorId, Role.WORKER);
        transferService.acceptTransfer(workerRequestorId, transferId, null);

        // Owner rejects
        authenticateAs(ownerCustomerId, Role.CUSTOMER);
        var rejected = transferService.rejectTransfer(ownerCustomerId, transferId);
        assertThat(rejected.status()).isEqualTo(TransferStatus.PENDING);

        // Package should still be CREATED with no custodian change
        var pkg = packageService.getPackageById(created.id());
        assertThat(pkg.status()).isEqualTo(PackageStatus.CREATED);
    }

    // ── Owner cannot request their own transfer ────────────────────────────

    @Test
    void confirmTransfer_ownerCannotRequestOwnTransfer() {
        var workerOwnerId = createUser("worker-owner@test.com", Role.WORKER);
        createWorkerProfile(workerOwnerId);

        // Worker creates a CONFIRM transfer
        authenticateAs(workerOwnerId, Role.WORKER);
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, workerOwnerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Destination", null, null),
                null,
                TransferRuleType.CONFIRM,
                null,
                null
        );
        var creation = packageService.createPackage(workerOwnerId, Role.WORKER, null, input);
        var transferId = creation.transfer().id();

        // Owner (same user) tries to request their own transfer → should fail
        authenticateAs(workerOwnerId, Role.WORKER);
        assertThatThrownBy(() -> transferService.acceptTransfer(workerOwnerId, transferId, null))
                .isInstanceOf(com.gocavgo.delivary.exception.BusinessValidationException.class)
                .hasMessageContaining("cannot request their own transfer");
    }
}
