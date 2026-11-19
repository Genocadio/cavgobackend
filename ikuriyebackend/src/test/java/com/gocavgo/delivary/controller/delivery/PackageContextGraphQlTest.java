package com.gocavgo.delivary.controller.delivery;

import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.user.UserStatus;
import com.gocavgo.delivary.repository.trip.CavgoTripJpaRepository;
import com.gocavgo.delivary.repository.user.UserJpaRepository;
import com.gocavgo.delivary.service.delivery.PackageService;
import com.gocavgo.delivary.testsupport.TripTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Verifies the new package-context fields are exposed and resolved by GraphQL. */
@SpringBootTest
@Transactional
class PackageContextGraphQlTest {

    private static final String CONTEXT_QUERY = """
            query PackageContext($id: ID!) {
              package(id: $id) {
                id
                tripId
                companyRefId
                packageOrigin
                tripIds
                companyIds
                companyRefIds
                carrierLegs { driverId tripId companyRefId custodianRole }
              }
            }
            """;

    @Autowired
    private ExecutionGraphQlService executionGraphQlService;

    @Autowired
    private PackageService packageService;

    @Autowired
    private UserJpaRepository userRepo;

    @Autowired
    private CavgoTripJpaRepository cavgoTripRepo;

    private long nextUserId = 8_000_000L;

    private Long createUser(String email) {
        return userRepo.save(UserEntity.builder()
                .id(nextUserId++)
                .email(email)
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build()).getId();
    }

    @Test
    void driverAcceptedPackage_exposesContextListsViaGraphQl() {
        var customerId = createUser("gql-c@test.com");
        var driverId = createUser("gql-d@test.com");
        var trip = TripTestFixtures.createActiveTrip(cavgoTripRepo, driverId, 444L);

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
        var packageId = creation.deliveryPackage().id();

        var auth = new UsernamePasswordAuthenticationToken(
                driverId.toString(), null,
                List.of(new SimpleGrantedAuthority("ROLE_DRIVER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        packageService.acceptPackageByTransfer(driverId, creation.transfer().id(), null, null);

        GraphQlTester graphQlTester = ExecutionGraphQlServiceTester.create(executionGraphQlService);
        graphQlTester.document(CONTEXT_QUERY)
                .variable("id", packageId.toString())
                .execute()
                .errors().verify()
                .path("data.package.tripId").entity(String.class).isEqualTo(trip.getTripId().toString())
                .path("data.package.companyRefId").entity(String.class).isEqualTo("444")
                .path("data.package.packageOrigin").entity(String.class).isEqualTo("FLEET")
                .path("data.package.tripIds").entityList(String.class).hasSize(1)
                .path("data.package.companyRefIds").entityList(String.class).hasSize(1)
                .path("data.package.carrierLegs").entityList(Object.class).hasSize(1);
    }
}
