package com.gocavgo.delivary.service.notification;

import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeServiceAssignmentObjectionTest {

    @Mock private NoticeRepository noticeRepo;
    @Mock private NoticeViewerRepository viewerRepo;
    @Mock private PackagePersonJpaRepository personRepo;
    @Mock private PackageCustodianJpaRepository custodianRepo;
    @Mock private TransferPackageJpaRepository transferPackageRepo;
    @Mock private PackageJpaRepository packageRepo;
    @Mock private NoticePublisher noticePublisher;
    @Mock private UserService userService;

    @Test
    void objectionNoticeGoesToAssigningWorkerNotTheDriver() {
        var service = new NoticeService(noticeRepo, viewerRepo, personRepo, custodianRepo,
                transferPackageRepo, packageRepo, noticePublisher, userService);
        var packageId = UUID.randomUUID();
        var now = Instant.parse("2026-11-26T10:00:00Z");
        var pkg = PackageEntity.builder()
                .id(packageId)
                .trackingCode("CAV-900")
                .deliveryType(DeliveryType.FIXED_ROUTE)
                .status(PackageStatus.ASSIGNED_DRIVER)
                .creatorId(12L)
                .packageOrigin(PackageOrigin.FLEET)
                .createdAt(now)
                .updatedAt(now)
                .build();
        var assignment = PackageAssignmentEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .driverId(77L)
                .assignedBy(12L)
                .assignedAt(now)
                .build();

        when(custodianRepo.findByPackageId(packageId)).thenReturn(List.of(
                custodian(packageId, 77L), custodian(packageId, 12L), custodian(packageId, 55L)));
        var savedNotice = new java.util.concurrent.atomic.AtomicReference<NoticeEntity>();
        when(noticeRepo.save(any())).thenAnswer(invocation -> {
            NoticeEntity notice = invocation.getArgument(0);
            notice.setId(UUID.randomUUID());
            savedNotice.set(notice);
            return notice;
        });
        when(noticeRepo.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(savedNotice.get()));
        when(viewerRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.notifyAssignmentObjected(pkg, assignment, 77L, 12L, "not physically received");

        var noticeCaptor = ArgumentCaptor.forClass(NoticeEntity.class);
        verify(noticeRepo).save(noticeCaptor.capture());
        assertEquals(NoticeEventType.PACKAGE_ASSIGNMENT_OBJECTED, noticeCaptor.getValue().getEventType());
        assertTrue(noticeCaptor.getValue().getPayload().contains("not physically received"));

        var viewersCaptor = ArgumentCaptor.forClass(List.class);
        verify(viewerRepo).saveAll(viewersCaptor.capture());
        List<NoticeViewerEntity> viewers = viewersCaptor.getValue();
        assertEquals(2, viewers.size());
        assertTrue(viewers.stream().anyMatch(v -> v.getUserId().equals(12L)));
        assertTrue(viewers.stream().anyMatch(v -> v.getUserId().equals(55L)));
        assertFalse(viewers.stream().anyMatch(v -> v.getUserId().equals(77L)));
    }

    private static PackageCustodianEntity custodian(UUID packageId, Long userId) {
        return PackageCustodianEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .userId(userId)
                .role(CustodianRole.DRIVER)
                .assignedAt(Instant.parse("2026-11-26T10:00:00Z"))
                .build();
    }
}
