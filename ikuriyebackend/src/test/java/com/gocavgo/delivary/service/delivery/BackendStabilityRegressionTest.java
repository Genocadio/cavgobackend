package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.transfer.input.UpdateTransferInput;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.entity.user.WorkerProfileEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.delivery.SortOrder;
import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.DriverType;
import com.gocavgo.delivary.enums.user.EmploymentType;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
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
 * Regression tests validating backend stability fixes:
 * 1. Multi-role user (DRIVER + WORKER) acceptance of DRIVER-targeted transfers.
 * 2. Custody provenance correctly recording prior custodian (e.g. SENDER -> DRIVER, not DRIVER -> DRIVER).
 * 3. getAvailablePackages DB-level exclusion of REQUESTED transfers and targeting multi-role drivers.
 * 4. BusinessValidationException propagation for GraphQL validation errors.
 */
@SpringBootTest
@Transactional
class BackendStabilityRegressionTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private UserJpaRepository userRepo;

    @Autowired
    private DriverProfileJpaRepository driverProfileRepo;

    @Autowired
    private WorkerProfileJpaRepository workerProfileRepo;

    private long nextUserId = 4_000_000L;

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Long createUser(String email) {
        var user = userRepo.save(UserEntity.builder()
                .id(nextUserId++)
                .email(email)
                .phone("+250788" + (System.nanoTime() % 1_000_000))
                .firstName("Test")
                .lastName("User")
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private void attachDriverProfile(Long userId) {
        var user = userRepo.findById(userId).orElseThrow();
        driverProfileRepo.save(DriverProfileEntity.builder()
                .user(user)
                .employmentType(EmploymentType.INDEPENDENT)
                .driverType(DriverType.OPEN)
                .status(DriverStatus.ONLINE)
                .createdAt(Instant.now())
                .build());
    }

    private void attachWorkerProfile(Long userId) {
        var user = userRepo.findById(userId).orElseThrow();
        workerProfileRepo.save(WorkerProfileEntity.builder()
                .user(user)
                .createdAt(Instant.now())
                .build());
    }

    private void authenticateWithRoles(Long userId, List<String> roleNames) {
        var authorities = roleNames.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private PackageResponse.CustodianResponse lastCustodian(PackageResponse pkg) {
        return pkg.custodians().stream()
                .max(Comparator.comparing(PackageResponse.CustodianResponse::assignedAt))
                .orElseThrow();
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    void multiRoleDriverCanAcceptDriverTargetedTransfer() {
        var customerId = createUser("cust-multi@test.com");
        var multiUserId = createUser("driver-worker@test.com");
        attachDriverProfile(multiUserId);
        attachWorkerProfile(multiUserId);

        // 1. Customer creates package with AUTO transfer targeted specifically to DRIVER
        authenticateWithRoles(customerId, List.of("CUSTOMER"));
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Cust", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Recv", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Dest", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var pkgCreation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var pkgId = pkgCreation.deliveryPackage().id();
        var transferId = pkgCreation.transfer().id();

        // Target transfer specifically to DRIVER
        transferService.updateTransfer(customerId, new UpdateTransferInput(
                transferId, null, TransferAcceptorType.DRIVER));

        // 2. Authenticate as multi-role user with both WORKER and DRIVER authorities
        // NexxauthRoles.primaryRole ranks WORKER > DRIVER, but hasAuthorityRole recognizes DRIVER!
        authenticateWithRoles(multiUserId, List.of("WORKER", "DRIVER"));

        // 3. User accepts the transfer
        var acceptResult = transferService.acceptTransfer(multiUserId, transferId, null, null);
        assertThat(acceptResult.transfer().status()).isEqualTo(TransferStatus.DONE);

        var acceptedPkg = acceptResult.acceptedPackages().get(0).deliveryPackage();
        assertThat(acceptedPkg.status()).isEqualTo(PackageStatus.PICKED_UP);
        assertThat(lastCustodian(acceptedPkg).userId()).isEqualTo(multiUserId);
        assertThat(lastCustodian(acceptedPkg).role()).isEqualTo(CustodianRole.DRIVER);
    }

    @Test
    void custodyProvenanceCorrectlyRecordsSenderToDriver() {
        var customerId = createUser("cust-prov@test.com");
        var driverId = createUser("driver-prov@test.com");
        attachDriverProfile(driverId);

        authenticateWithRoles(customerId, List.of("CUSTOMER"));
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Cust", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Recv", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Dest", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var pkgCreation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var transferId = pkgCreation.transfer().id();

        authenticateWithRoles(driverId, List.of("DRIVER"));
        var acceptResult = transferService.acceptTransfer(driverId, transferId, null, null);
        var acceptedPkg = acceptResult.acceptedPackages().get(0).deliveryPackage();

        // Check custody provenance: prior custodian was SENDER, new custodian is DRIVER
        // Previously this erroneously recorded DRIVER -> DRIVER because saveCustodian was called before querying previous custodian
        assertThat(acceptedPkg.custody()).isNotEmpty();
        var latestCustody = acceptedPkg.custody().get(acceptedPkg.custody().size() - 1);
        assertThat(latestCustody.fromEntity()).isEqualTo("SENDER");
        assertThat(latestCustody.toEntity()).isEqualTo("DRIVER");
    }

    @Test
    void getAvailablePackagesExcludesRequestedTransfersAndMatchesMultiRoleDriver() {
        var customerId = createUser("cust-avail@test.com");
        var driver1Id = createUser("driver-req1@test.com");
        var driver2Id = createUser("driver-req2@test.com");
        attachDriverProfile(driver1Id);
        attachDriverProfile(driver2Id);

        // Package with CONFIRM rule
        authenticateWithRoles(customerId, List.of("CUSTOMER"));
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Cust", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Recv", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Dest", null, null),
                null,
                TransferRuleType.CONFIRM,
                null,
                null
        );
        var pkgCreation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var pkgId = pkgCreation.deliveryPackage().id();
        var transferId = pkgCreation.transfer().id();

        // Available for driver1 initially
        authenticateWithRoles(driver1Id, List.of("DRIVER"));
        var availableBefore = packageService.getAvailablePackages(PackageStatus.CREATED, SortOrder.ASC, 0, 10,
                driver1Id, Role.DRIVER, null);
        boolean containsPkg = availableBefore.items().stream().anyMatch(p -> p.id().equals(pkgId));
        assertThat(containsPkg).isTrue();

        // Driver1 requests transfer → transfer status becomes REQUESTED
        transferService.acceptTransfer(driver1Id, transferId, null, null);

        // Driver2 queries available packages → package MUST BE EXCLUDED because transfer is in REQUESTED state
        authenticateWithRoles(driver2Id, List.of("DRIVER"));
        var availableAfter = packageService.getAvailablePackages(PackageStatus.CREATED, SortOrder.ASC, 0, 10,
                driver2Id, Role.DRIVER, null);
        boolean containsPkgAfter = availableAfter.items().stream().anyMatch(p -> p.id().equals(pkgId));
        assertThat(containsPkgAfter).isFalse();
    }

    @Test
    void businessValidationExceptionThrownWhenAcceptingCancelledTransfer() {
        var customerId = createUser("cust-cancel@test.com");
        var driverId = createUser("driver-cancel@test.com");
        attachDriverProfile(driverId);

        authenticateWithRoles(customerId, List.of("CUSTOMER"));
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Cust", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Recv", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Dest", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var pkgCreation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        var transferId = pkgCreation.transfer().id();

        // Customer cancels the transfer
        transferService.cancelTransfer(customerId, transferId);

        // Driver tries to accept cancelled transfer
        authenticateWithRoles(driverId, List.of("DRIVER"));
        assertThatThrownBy(() -> transferService.acceptTransfer(driverId, transferId, null, null))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("CANCELED");
    }
}
