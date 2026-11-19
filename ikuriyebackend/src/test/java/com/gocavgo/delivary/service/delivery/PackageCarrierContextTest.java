package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.controller.delivery.PackageContextResolver;
import com.gocavgo.delivary.dto.delivery.input.AssignDriverInput;
import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.user.DriverProfileEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.user.DriverStatus;
import com.gocavgo.delivary.enums.user.DriverType;
import com.gocavgo.delivary.enums.user.EmploymentType;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageOrigin;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.PackageCarrierLegJpaRepository;
import com.gocavgo.delivary.repository.trip.CavgoTripJpaRepository;
import com.gocavgo.delivary.repository.user.DriverProfileJpaRepository;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.testsupport.TripTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Scope A: a DRIVER must be tied to a real active trip when taking custody, and
 * the resulting trip/company/vehicle context is snapshotted as carrier legs so
 * admin reporting can follow a package through multiple drivers.
 */
@SpringBootTest
@Transactional
class PackageCarrierContextTest {

    @Autowired
    private PackageService packageService;

    @Autowired
    private UserJpaRepository userRepo;

    @Autowired
    private CavgoTripJpaRepository cavgoTripRepo;

    @Autowired
    private PackageCarrierLegJpaRepository carrierLegRepo;

    @Autowired
    private PackageContextResolver packageContextResolver;

    @Autowired
    private DriverProfileJpaRepository driverProfileRepo;

    private record CreatedPackage(java.util.UUID packageId, java.util.UUID transferId) {
    }

    private long nextUserId = 7_000_000L;

    private void authenticateAs(Long userId, Role role) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Long createUser(String email) {
        var user = userRepo.save(UserEntity.builder()
                .id(nextUserId++)
                .email(email)
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return user.getId();
    }

    private void makeDriver(Long userId) {
        driverProfileRepo.save(DriverProfileEntity.builder()
                .user(userRepo.findById(userId).orElseThrow())
                .employmentType(EmploymentType.INDEPENDENT)
                .driverType(DriverType.OPEN)
                .status(DriverStatus.ONLINE)
                .createdAt(Instant.now())
                .build());
    }

    /** Creates an OPEN package with an AUTO transfer and returns (packageId, transferId). */
    private CreatedPackage createOpenPackage(Long customerId) {
        var input = new CreatePackageInput(
                DeliveryType.OPEN,
                new CreatePackageInput.PersonInput(PersonRole.SENDER, customerId, "Alice", "+123"),
                new CreatePackageInput.PersonInput(PersonRole.RECEIVER, null, "Bob", "+456"),
                new CreatePackageInput.LocationInput(LocationType.ORIGIN, 0.0, 0.0, "Origin", null, null),
                new CreatePackageInput.LocationInput(LocationType.DESTINATION, 0.0, 0.0, "Dest", null, null),
                null,
                TransferRuleType.AUTO,
                null,
                null
        );
        var creation = packageService.createPackage(customerId, Role.CUSTOMER, null, input);
        return new CreatedPackage(creation.deliveryPackage().id(), creation.transfer().id());
    }

    @Test
    void driverWithoutActiveTrip_cannotAcceptTransfer() {
        var customerId = createUser("ctx-c1@test.com");
        var driverId = createUser("ctx-d1@test.com");
        var created = createOpenPackage(customerId);
        var transferId = created.transferId();

        authenticateAs(driverId, Role.DRIVER);
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(driverId, transferId, null, null))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("has no active trip");
    }

    @Test
    void tripOwnedByAnotherDriver_isRejected() {
        var customerId = createUser("ctx-c2@test.com");
        var driverId = createUser("ctx-d2@test.com");
        var otherDriverId = createUser("ctx-d2b@test.com");
        var otherTrip = TripTestFixtures.createActiveTrip(cavgoTripRepo, otherDriverId);
        var created = createOpenPackage(customerId);
        var transferId = created.transferId();

        authenticateAs(driverId, Role.DRIVER);
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(
                driverId, transferId, null, otherTrip.getTripId()))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("does not belong to driver");
    }

    @Test
    void inactiveTrip_isRejected() {
        var customerId = createUser("ctx-c3@test.com");
        var driverId = createUser("ctx-d3@test.com");
        var completedTrip = TripTestFixtures.createTrip(cavgoTripRepo, driverId, 777L, "COMPLETED");
        var created = createOpenPackage(customerId);
        var transferId = created.transferId();

        authenticateAs(driverId, Role.DRIVER);
        assertThatThrownBy(() -> packageService.acceptPackageByTransfer(
                driverId, transferId, null, completedTrip.getTripId()))
                .isInstanceOf(BusinessValidationException.class)
                .hasMessageContaining("is not active");
    }

    @Test
    void driverAcceptance_derivesActiveTripAndLinksContext() {
        var customerId = createUser("ctx-c4@test.com");
        var driverId = createUser("ctx-d4@test.com");
        var trip = TripTestFixtures.createActiveTrip(cavgoTripRepo, driverId, 555L);
        var created = createOpenPackage(customerId);
        var packageId = created.packageId();
        var transferId = created.transferId();

        authenticateAs(driverId, Role.DRIVER);
        var result = packageService.acceptPackageByTransfer(driverId, transferId, null, null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();

        assertThat(accepted.tripId()).isEqualTo(trip.getTripId());
        assertThat(accepted.companyRefId()).isEqualTo(555L);
        assertThat(accepted.packageOrigin()).isEqualTo(PackageOrigin.FLEET);
        assertThat(accepted.carrierLegs()).hasSize(1);
        var leg = accepted.carrierLegs().get(0);
        assertThat(leg.driverId()).isEqualTo(driverId);
        assertThat(leg.tripId()).isEqualTo(trip.getTripId());
        assertThat(leg.companyRefId()).isEqualTo(555L);
        assertThat(leg.custodianRole()).isEqualTo(CustodianRole.DRIVER);
        assertThat(carrierLegRepo.findByPackageIdOrderByLinkedAtAsc(packageId)).hasSize(1);
    }

    @Test
    void driverAcceptance_withExplicitTripId_linksThatTrip() {
        var customerId = createUser("ctx-c5@test.com");
        var driverId = createUser("ctx-d5@test.com");
        var trip = TripTestFixtures.createActiveTrip(cavgoTripRepo, driverId, 888L);
        var created = createOpenPackage(customerId);
        var transferId = created.transferId();

        authenticateAs(driverId, Role.DRIVER);
        var result = packageService.acceptPackageByTransfer(driverId, transferId, null, trip.getTripId());
        var accepted = result.acceptedPackages().get(0).deliveryPackage();

        assertThat(accepted.tripId()).isEqualTo(trip.getTripId());
        assertThat(accepted.companyRefId()).isEqualTo(888L);
    }

    @Test
    void workerAcceptance_leavesPackagePrivateWithNoTrip() {
        var customerId = createUser("ctx-c6@test.com");
        var workerId = createUser("ctx-d6@test.com");
        var created = createOpenPackage(customerId);
        var transferId = created.transferId();

        authenticateAs(workerId, Role.WORKER);
        var result = packageService.acceptPackageByTransfer(workerId, transferId, null, null);
        var accepted = result.acceptedPackages().get(0).deliveryPackage();

        assertThat(accepted.tripId()).isNull();
        assertThat(accepted.companyRefId()).isNull();
        assertThat(accepted.packageOrigin()).isEqualTo(PackageOrigin.PRIVATE);
        assertThat(accepted.carrierLegs()).isEmpty();
    }

    @Test
    void carrierLegs_accumulateAcrossDriverAssignments() {
        var customerId = createUser("ctx-c7@test.com");
        var adminId = createUser("ctx-admin@test.com");
        var driver1 = createUser("ctx-d7a@test.com");
        var driver2 = createUser("ctx-d7b@test.com");
        makeDriver(driver1);
        makeDriver(driver2);
        var trip1 = TripTestFixtures.createActiveTrip(cavgoTripRepo, driver1, 111L);
        var trip2 = TripTestFixtures.createActiveTrip(cavgoTripRepo, driver2, 222L);
        var created = createOpenPackage(customerId);
        var packageId = created.packageId();

        authenticateAs(adminId, Role.ADMIN);
        packageService.assignDriver(new AssignDriverInput(packageId, driver1, adminId, null, trip1.getTripId()));
        var afterFirst = packageService.getPackageById(packageId);
        assertThat(afterFirst.carrierLegs()).hasSize(1);

        // Second assignment by the same admin (trusted office staff) appends a leg.
        packageService.assignDriver(new AssignDriverInput(packageId, driver2, adminId, null, trip2.getTripId()));
        var afterSecond = packageService.getPackageById(packageId);

        assertThat(afterSecond.carrierLegs()).hasSize(2);
        assertThat(afterSecond.carrierLegs())
                .extracting(PackageResponse.CarrierLegResponse::driverId)
                .containsExactly(driver1, driver2);
        assertThat(packageContextResolver.tripIds(afterSecond))
                .containsExactly(trip1.getTripId(), trip2.getTripId());
        assertThat(packageContextResolver.companyRefIds(afterSecond)).containsExactly(111L, 222L);
    }
}
