package com.gocavgo.delivary.service.notification;

import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.notification.NoticeEntity;
import com.gocavgo.delivary.entity.notification.NoticeViewerEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageOrigin;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackagePersonJpaRepository;
import com.gocavgo.delivary.repository.notification.NoticeRepository;
import com.gocavgo.delivary.repository.notification.NoticeViewerRepository;
import com.gocavgo.delivary.repository.transfer.TransferPackageJpaRepository;
import com.gocavgo.delivary.service.subscription.NoticePublisher;
import com.gocavgo.delivary.service.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeServiceOfficeClaimTest {

    private static final Long WORKER_ID = 12L;
    private static final Long DRIVER_ID = 77L;

    @Mock private NoticeRepository noticeRepo;
    @Mock private NoticeViewerRepository viewerRepo;
    @Mock private PackagePersonJpaRepository personRepo;
    @Mock private PackageCustodianJpaRepository custodianRepo;
    @Mock private TransferPackageJpaRepository transferPackageRepo;
    @Mock private PackageJpaRepository packageRepo;
    @Mock private NoticePublisher noticePublisher;
    @Mock private UserService userService;

    private NoticeService service;
    private final AtomicReference<NoticeEntity> savedNotice = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new NoticeService(noticeRepo, viewerRepo, personRepo, custodianRepo,
                transferPackageRepo, packageRepo, noticePublisher, userService);
        when(noticeRepo.save(any())).thenAnswer(invocation -> {
            NoticeEntity notice = invocation.getArgument(0);
            notice.setId(UUID.randomUUID());
            savedNotice.set(notice);
            return notice;
        });
        when(noticeRepo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedNotice.get()));
        when(viewerRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void claimCreatedNoticeGoesToDriverNotTheClaimingWorker() {
        var packageId = UUID.randomUUID();
        var pkg = pkg(packageId);
        var claim = claim(packageId, "dock handoff");
        when(custodianRepo.findByPackageId(packageId)).thenReturn(List.of(
                custodian(packageId, DRIVER_ID), custodian(packageId, WORKER_ID)));

        service.notifyOfficeClaimCreated(pkg, claim, WORKER_ID, DRIVER_ID);

        var noticeCaptor = ArgumentCaptor.forClass(NoticeEntity.class);
        verify(noticeRepo).save(noticeCaptor.capture());
        assertEquals(NoticeEventType.PACKAGE_OFFICE_CLAIM_CREATED, noticeCaptor.getValue().getEventType());
        assertTrue(noticeCaptor.getValue().getPayload().contains("dock handoff"));

        var viewersCaptor = ArgumentCaptor.forClass(List.class);
        verify(viewerRepo).saveAll(viewersCaptor.capture());
        List<NoticeViewerEntity> viewers = viewersCaptor.getValue();
        assertEquals(1, viewers.size());
        assertEquals(DRIVER_ID, viewers.get(0).getUserId());
    }

    @Test
    void disputeNoticeGoesToClaimingWorkerNotTheDriver() {
        var packageId = UUID.randomUUID();
        var pkg = pkg(packageId);
        var claim = claim(packageId, "dock handoff");
        when(custodianRepo.findByPackageId(packageId)).thenReturn(List.of(
                custodian(packageId, DRIVER_ID), custodian(packageId, WORKER_ID)));

        service.notifyOfficeClaimDisputed(pkg, claim, DRIVER_ID, WORKER_ID, "driver did not hand it over");

        var noticeCaptor = ArgumentCaptor.forClass(NoticeEntity.class);
        verify(noticeRepo).save(noticeCaptor.capture());
        assertEquals(NoticeEventType.PACKAGE_OFFICE_CLAIM_DISPUTED, noticeCaptor.getValue().getEventType());
        assertTrue(noticeCaptor.getValue().getPayload().contains("driver did not hand it over"));

        var viewersCaptor = ArgumentCaptor.forClass(List.class);
        verify(viewerRepo).saveAll(viewersCaptor.capture());
        List<NoticeViewerEntity> viewers = viewersCaptor.getValue();
        assertEquals(1, viewers.size());
        assertEquals(WORKER_ID, viewers.get(0).getUserId());
    }

    private static PackageEntity pkg(UUID packageId) {
        var now = Instant.parse("2026-11-26T10:00:00Z");
        return PackageEntity.builder()
                .id(packageId)
                .trackingCode("CAV-900")
                .deliveryType(DeliveryType.FIXED_ROUTE)
                .status(PackageStatus.DESTINATION_OFFICE)
                .creatorId(DRIVER_ID)
                .packageOrigin(PackageOrigin.FLEET)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static OfficeClaimEntity claim(UUID packageId, String notes) {
        var now = Instant.parse("2026-11-26T10:00:00Z");
        return OfficeClaimEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .driverId(DRIVER_ID)
                .claimedBy(WORKER_ID)
                .officeLocationId(UUID.randomUUID())
                .claimedAt(now)
                .disputeDeadline(now.plusSeconds(600))
                .notes(notes)
                .build();
    }

    private static PackageCustodianEntity custodian(UUID packageId, Long userId) {
        return PackageCustodianEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .userId(userId)
                .role(CustodianRole.OFFICE)
                .assignedAt(Instant.parse("2026-11-26T10:00:00Z"))
                .build();
    }
}
