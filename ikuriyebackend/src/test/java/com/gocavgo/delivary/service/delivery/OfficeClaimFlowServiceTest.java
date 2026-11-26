package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageOrigin;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.SortOrder;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.OfficeClaimJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.service.notification.NoticeService;
import com.gocavgo.delivary.service.transfer.TransferService;
import com.gocavgo.delivary.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OfficeClaimFlowServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(10 * 60);
    private static final Long WORKER_ID = 12L;
    private static final Long DRIVER_ID = 77L;
    private static final UUID OFFICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock private PackageJpaRepository packageRepo;
    @Mock private PackageLocationJpaRepository packageLocationRepo;
    @Mock private PackageCustodianJpaRepository custodianRepo;
    @Mock private OfficeClaimJpaRepository claimRepo;
    @Mock private PackageService packageService;
    @Mock private UserService userService;
    @Mock private NoticeService noticeService;
    @Mock private TransferService transferService;

    private OfficeClaimFlowService service;

    @BeforeEach
    void setUp() {
        service = new OfficeClaimFlowService(
                packageRepo,
                packageLocationRepo,
                custodianRepo,
                claimRepo,
                packageService,
                userService,
                noticeService,
                transferService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void incomingQueueFindsDriverHeldPackagesDestinedForTheWorkersOffice() {
        var p1 = pkg("11111111-1111-1111-1111-111111111111");
        var p2 = pkg("22222222-2222-2222-2222-222222222222");
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID)))
                .thenReturn(List.of(p1.getId(), p2.getId()));
        when(packageRepo.findAllById(List.of(p1.getId(), p2.getId()))).thenReturn(List.of(p1, p2));
        when(packageService.getPackageById(p1.getId())).thenReturn(mock(PackageResponse.class));
        when(packageService.getPackageById(p2.getId())).thenReturn(mock(PackageResponse.class));

        var page = service.getIncomingPackagesForOffice(WORKER_ID, null, 0, 20);

        assertEquals(2, page.items().size());
        assertEquals(2, page.totalCount());
        assertEquals(0, page.currentPage());
    }

    @Test
    void incomingQueueShowsFullOfficeLifecycleButKeepsCancelledPackagesHidden() {
        var inTransit = pkg("11111111-1111-1111-1111-111111111111");
        var delivered = pkg("22222222-2222-2222-2222-222222222222");
        delivered.setStatus(PackageStatus.DELIVERED);
        var cancelled = pkg("33333333-3333-3333-3333-333333333333");
        cancelled.setStatus(PackageStatus.CANCELLED);
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID)))
                .thenReturn(List.of(inTransit.getId(), delivered.getId(), cancelled.getId()));
        when(packageRepo.findAllById(any())).thenReturn(List.of(inTransit, delivered, cancelled));
        when(packageService.getPackageById(any())).thenAnswer(invocation -> mock(PackageResponse.class));

        var page = service.getIncomingPackagesForOffice(WORKER_ID, null, 0, 20);

        assertEquals(2, page.items().size());
        assertEquals(2, page.totalCount());
    }

    @Test
    void outgoingQueueFindsPackagesCreatedFromTheWorkersOfficeAcrossActiveStatuses() {
        var created = pkg("11111111-1111-1111-1111-111111111111");
        created.setStatus(PackageStatus.CREATED);
        var inTransit = pkg("22222222-2222-2222-2222-222222222222");
        var delivered = pkg("33333333-3333-3333-3333-333333333333");
        delivered.setStatus(PackageStatus.DELIVERED);
        var cancelled = pkg("44444444-4444-4444-4444-444444444444");
        cancelled.setStatus(PackageStatus.CANCELLED);
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.ORIGIN, List.of(OFFICE_ID)))
                .thenReturn(List.of(created.getId(), inTransit.getId(), delivered.getId(), cancelled.getId()));
        when(packageRepo.findAllById(any())).thenReturn(List.of(created, inTransit, delivered, cancelled));
        when(packageService.getPackageById(any())).thenAnswer(invocation -> mock(PackageResponse.class));

        var page = service.getOutgoingPackagesForOffice(WORKER_ID, null, 0, 20);

        assertEquals(3, page.items().size());
        assertEquals(3, page.totalCount());
        verify(packageLocationRepo).findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.ORIGIN, List.of(OFFICE_ID));
    }

    @Test
    void officeQueuesHonorSortOrder() {
        var older = pkg("11111111-1111-1111-1111-111111111111");
        older.setUpdatedAt(NOW.minusSeconds(600));
        var newer = pkg("22222222-2222-2222-2222-222222222222");
        newer.setUpdatedAt(NOW.minusSeconds(60));
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID)))
                .thenReturn(List.of(older.getId(), newer.getId()));
        when(packageRepo.findAllById(any())).thenReturn(List.of(older, newer));
        when(packageService.getPackageById(any())).thenAnswer(invocation -> {
            var response = mock(PackageResponse.class);
            when(response.id()).thenReturn(invocation.getArgument(0));
            return response;
        });

        var ascending = service.getIncomingPackagesForOffice(WORKER_ID, null, SortOrder.ASC, 0, 20);
        var descending = service.getIncomingPackagesForOffice(WORKER_ID, null, SortOrder.DESC, 0, 20);

        assertEquals(List.of(older.getId(), newer.getId()),
                ascending.items().stream().map(PackageResponse::id).toList());
        assertEquals(List.of(newer.getId(), older.getId()),
                descending.items().stream().map(PackageResponse::id).toList());
    }

    @Test
    void outgoingQueuePaginatesWithTotalCounts() {
        var first = pkg("11111111-1111-1111-1111-111111111111");
        first.setUpdatedAt(NOW.minusSeconds(300));
        var second = pkg("22222222-2222-2222-2222-222222222222");
        second.setUpdatedAt(NOW.minusSeconds(200));
        var third = pkg("33333333-3333-3333-3333-333333333333");
        third.setUpdatedAt(NOW.minusSeconds(100));
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.ORIGIN, List.of(OFFICE_ID)))
                .thenReturn(List.of(first.getId(), second.getId(), third.getId()));
        when(packageRepo.findAllById(any())).thenReturn(List.of(first, second, third));
        when(packageService.getPackageById(any())).thenAnswer(invocation -> mock(PackageResponse.class));

        var page = service.getOutgoingPackagesForOffice(WORKER_ID, null, SortOrder.DESC, 1, 2);

        assertEquals(1, page.items().size());
        assertEquals(3, page.totalCount());
        assertEquals(2, page.totalPages());
        assertEquals(1, page.currentPage());
    }

    @Test
    void bulkClaimSetsOfficeCustodyAndDisputeWindowForEveryPackage() {
        var p1 = pkg("11111111-1111-1111-1111-111111111111");
        var p2 = pkg("22222222-2222-2222-2222-222222222222");
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageRepo.findAllById(List.of(p1.getId(), p2.getId()))).thenReturn(List.of(p1, p2));
        when(packageLocationRepo.findByTypeAndOfficeLocationIdInAndPackageIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID), List.of(p1.getId(), p2.getId())))
                .thenReturn(List.of(destinationLocation(p1.getId()), destinationLocation(p2.getId())));
        when(custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(any())).thenAnswer(invocation ->
                Optional.of(driverCustodian(invocation.getArgument(0))));
        when(packageService.claimFromDriver(any(), any(), any())).thenReturn(mock(PackageResponse.class));
        when(claimRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.claimPackagesFromDrivers(
                List.of(p1.getId(), p2.getId()), WORKER_ID, OFFICE_ID, "dock handoff"
        );

        assertEquals(2, result.claims().size());
        assertTrue(result.claims().stream().allMatch(c -> c.driverId().equals(DRIVER_ID)));
        assertTrue(result.claims().stream().allMatch(c -> c.claimedBy().equals(WORKER_ID)));
        assertTrue(result.claims().stream().allMatch(c -> c.officeId().equals(OFFICE_ID)));
        assertTrue(result.claims().stream().allMatch(c -> c.disputeDeadline().equals(DEADLINE)));
        verify(packageService, times(2)).claimFromDriver(any(), any(), any());
        verify(transferService).cancelOpenTransfersForPackages(List.of(p1.getId(), p2.getId()), WORKER_ID);

        var claimCaptor = ArgumentCaptor.forClass(OfficeClaimEntity.class);
        verify(claimRepo, times(2)).save(claimCaptor.capture());
        assertTrue(claimCaptor.getAllValues().stream().allMatch(c -> c.getClaimedAt().equals(NOW)));
        assertTrue(claimCaptor.getAllValues().stream().allMatch(c -> c.getDisputeDeadline().equals(DEADLINE)));
        verify(noticeService, times(2)).notifyOfficeClaimCreated(any(), any(), any(), any());
    }

    @Test
    void claimRejectsPackageWhoseDestinationOfficeDoesNotMatchWorker() {
        var pkg = pkg("11111111-1111-1111-1111-111111111111");
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageRepo.findAllById(List.of(pkg.getId()))).thenReturn(List.of(pkg));
        when(packageLocationRepo.findByTypeAndOfficeLocationIdInAndPackageIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID), List.of(pkg.getId())))
                .thenReturn(List.of());

        var ex = assertThrows(BusinessValidationException.class, () -> service.claimPackagesFromDrivers(
                List.of(pkg.getId()), WORKER_ID, OFFICE_ID, null
        ));

        assertTrue(ex.getMessage().contains("destination office"));
        verifyNoInteractions(packageService);
    }

    @Test
    void claimRejectsPackageNotCurrentlyHeldByDriver() {
        var pkg = pkg("11111111-1111-1111-1111-111111111111");
        when(userService.getCompanyIdForUser(WORKER_ID)).thenReturn(OFFICE_ID);
        when(packageRepo.findAllById(List.of(pkg.getId()))).thenReturn(List.of(pkg));
        when(packageLocationRepo.findByTypeAndOfficeLocationIdInAndPackageIdIn(
                LocationType.DESTINATION, List.of(OFFICE_ID), List.of(pkg.getId())))
                .thenReturn(List.of(destinationLocation(pkg.getId())));
        when(custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId()))
                .thenReturn(Optional.of(officeCustodian(pkg.getId())));

        assertThrows(BusinessValidationException.class, () -> service.claimPackagesFromDrivers(
                List.of(pkg.getId()), WORKER_ID, OFFICE_ID, null
        ));

        verifyNoInteractions(packageService);
    }

    @Test
    void disputeWithinWindowFlagsDisputeKeepsCustodyAndNotifiesWorker() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var pkg = pkg(packageId.toString());
        pkg.setStatus(PackageStatus.DESTINATION_OFFICE);
        var claim = claim(packageId, NOW, null, null);
        when(claimRepo.findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(claim));
        when(claimRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
        when(packageService.getPackageById(packageId)).thenReturn(mock(PackageResponse.class));

        var result = service.disputeClaims(List.of(packageId), DRIVER_ID, "driver did not hand it over");

        assertEquals(1, result.claims().size());
        assertEquals(NOW, result.claims().get(0).disputedAt());
        assertEquals("driver did not hand it over", result.claims().get(0).disputeReason());
        assertEquals(PackageStatus.DESTINATION_OFFICE, pkg.getStatus());
        verify(noticeService).notifyOfficeClaimDisputed(pkg, claim, DRIVER_ID, WORKER_ID, "driver did not hand it over");
        verify(packageService, never()).claimFromDriver(any(), any(), any());
    }

    @Test
    void disputeAfterWindowIsRejected() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var claim = claim(packageId, NOW.minusSeconds(11 * 60), null, null);
        when(claimRepo.findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(claim));

        var ex = assertThrows(BusinessValidationException.class, () ->
                service.disputeClaims(List.of(packageId), DRIVER_ID, "late"));

        assertTrue(ex.getMessage().contains("Dispute window has expired"));
        verifyNoInteractions(noticeService);
    }

    @Test
    void disputeByDriverWhoWasNotClaimedFromIsRejected() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(claimRepo.findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.empty());

        assertThrows(BusinessValidationException.class, () ->
                service.disputeClaims(List.of(packageId), DRIVER_ID, "not mine"));

        verifyNoInteractions(noticeService);
    }

    @Test
    void repeatedDisputeIsIdempotentAndDoesNotDuplicateNotice() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var claim = claim(packageId, NOW, NOW, "first dispute");
        when(claimRepo.findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(claim));
        when(packageService.getPackageById(packageId)).thenReturn(mock(PackageResponse.class));

        var result = service.disputeClaims(List.of(packageId), DRIVER_ID, "duplicate");

        assertFalse(result.claims().isEmpty());
        assertEquals("first dispute", result.claims().get(0).disputeReason());
        verify(noticeService, never()).notifyOfficeClaimDisputed(any(), any(), any(), any(), any());
    }

    private static PackageEntity pkg(String id) {
        return PackageEntity.builder()
                .id(UUID.fromString(id))
                .trackingCode("CAV-" + id.substring(0, 4))
                .deliveryType(DeliveryType.FIXED_ROUTE)
                .status(PackageStatus.IN_TRANSIT)
                .creatorId(DRIVER_ID)
                .packageOrigin(PackageOrigin.FLEET)
                .createdAt(NOW.minusSeconds(300))
                .updatedAt(NOW.minusSeconds(300))
                .build();
    }

    private static PackageLocationEntity destinationLocation(UUID packageId) {
        return PackageLocationEntity.builder()
                .packageId(packageId)
                .type(LocationType.DESTINATION)
                .officeLocationId(OFFICE_ID)
                .placeName("Destination office")
                .build();
    }

    private static PackageCustodianEntity driverCustodian(UUID packageId) {
        return PackageCustodianEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .userId(DRIVER_ID)
                .role(CustodianRole.DRIVER)
                .assignedAt(NOW.minusSeconds(120))
                .build();
    }

    private static PackageCustodianEntity officeCustodian(UUID packageId) {
        return PackageCustodianEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .userId(WORKER_ID)
                .role(CustodianRole.OFFICE)
                .assignedAt(NOW.minusSeconds(120))
                .build();
    }

    private static OfficeClaimEntity claim(UUID packageId, Instant claimedAt,
                                           Instant disputedAt, String disputeReason) {
        return OfficeClaimEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .driverId(DRIVER_ID)
                .claimedBy(WORKER_ID)
                .officeLocationId(OFFICE_ID)
                .claimedAt(claimedAt)
                .disputeDeadline(claimedAt.plusSeconds(10 * 60))
                .disputedAt(disputedAt)
                .disputeReason(disputeReason)
                .build();
    }
}
