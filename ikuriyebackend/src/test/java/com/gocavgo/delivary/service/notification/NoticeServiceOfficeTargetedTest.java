package com.gocavgo.delivary.service.notification;

import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.notification.NoticeEntity;
import com.gocavgo.delivary.entity.notification.NoticeViewerEntity;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeServiceOfficeTargetedTest {

    private static final Long ACTOR_ID = 11L;
    private static final Long WORKER_ID = 12L;
    private static final UUID OFFICE_ID = UUID.randomUUID();

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
        lenient().when(noticeRepo.save(any())).thenAnswer(invocation -> {
            NoticeEntity notice = invocation.getArgument(0);
            notice.setId(UUID.randomUUID());
            savedNotice.set(notice);
            return notice;
        });
        lenient().when(noticeRepo.findById(any())).thenAnswer(invocation -> Optional.ofNullable(savedNotice.get()));
        lenient().when(viewerRepo.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void officeNoticeFansOutToOneViewerPerUserAndExcludesActor() {
        var pkg = pkg();
        when(userService.getUserIdsForOffice(OFFICE_ID)).thenReturn(Set.of(ACTOR_ID, WORKER_ID, 13L));

        service.notifyOfficePackageEvent(pkg, NoticeEventType.PACKAGE_OFFICE_UPDATE, ACTOR_ID,
                OFFICE_ID, "Incoming package", "Package CAV-900 needs intake");

        var noticeCaptor = ArgumentCaptor.forClass(NoticeEntity.class);
        verify(noticeRepo).save(noticeCaptor.capture());
        var notice = noticeCaptor.getValue();
        assertEquals(NoticeEventType.PACKAGE_OFFICE_UPDATE, notice.getEventType());
        assertEquals("Incoming package", notice.getTitle());
        assertTrue(notice.getPayload().contains(pkg.getId().toString()));
        assertTrue(notice.getPayload().contains(OFFICE_ID.toString()));

        var viewersCaptor = ArgumentCaptor.forClass(List.class);
        verify(viewerRepo).saveAll(viewersCaptor.capture());
        List<NoticeViewerEntity> viewers = viewersCaptor.getValue();
        assertEquals(2, viewers.size());
        assertTrue(viewers.stream().noneMatch(v -> ACTOR_ID.equals(v.getUserId())));
        verify(noticePublisher, times(2)).publish(any());
    }

    @Test
    void officeNoticeDoesNotWriteWhenOfficeHasNoUsers() {
        when(userService.getUserIdsForOffice(OFFICE_ID)).thenReturn(Set.of());

        service.notifyOfficePackageEvent(pkg(), NoticeEventType.PACKAGE_OFFICE_UPDATE, ACTOR_ID,
                OFFICE_ID, "Incoming package", "Package CAV-900 needs intake");

        verify(noticeRepo, never()).save(any());
        verify(viewerRepo, never()).saveAll(any());
        verify(noticePublisher, never()).publish(any());
    }

    private static PackageEntity pkg() {
        var now = Instant.parse("2026-11-26T10:00:00Z");
        return PackageEntity.builder()
                .id(UUID.randomUUID())
                .trackingCode("CAV-900")
                .deliveryType(DeliveryType.FIXED_ROUTE)
                .status(PackageStatus.CREATED)
                .creatorId(ACTOR_ID)
                .packageOrigin(PackageOrigin.FLEET)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
