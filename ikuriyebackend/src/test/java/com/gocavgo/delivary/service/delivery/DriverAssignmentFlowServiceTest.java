package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.AssignDriverInput;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageOrigin;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.PackageAssignmentJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.service.notification.NoticeService;
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
class DriverAssignmentFlowServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(10 * 60);
    private static final Long DRIVER_ID = 77L;
    private static final Long WORKER_ID = 12L;
    private static final Long TRIP_ID = 909L;

    @Mock
    private PackageJpaRepository packageRepo;

    @Mock
    private PackageService packageService;

    @Mock
    private PackageAssignmentJpaRepository assignmentRepo;

    @Mock
    private NoticeService noticeService;

    private DriverAssignmentFlowService service;

    @BeforeEach
    void setUp() {
        service = new DriverAssignmentFlowService(
                packageRepo,
                packageService,
                assignmentRepo,
                noticeService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void bulkAssignSetsAcceptedAndTenMinuteObjectionWindowForEveryPackage() {
        var p1 = pkg("11111111-1111-1111-1111-111111111111", "CAV-1");
        var p2 = pkg("22222222-2222-2222-2222-222222222222", "CAV-2");
        when(packageRepo.findAllById(List.of(p1.getId(), p2.getId()))).thenReturn(List.of(p1, p2));
        when(packageService.assignDriver(any())).thenReturn(mock(PackageResponse.class));

        var result = service.assignPackagesToDriver(
                List.of(p1.getId(), p2.getId()), DRIVER_ID, WORKER_ID, TRIP_ID, "morning batch"
        );

        assertEquals(2, result.packages().size());
        assertEquals(2, result.assignments().size());
        assertTrue(result.assignments().stream().allMatch(a -> a.acceptedAt().equals(NOW)));
        assertTrue(result.assignments().stream().allMatch(a -> a.objectionDeadline().equals(DEADLINE)));
        assertEquals(result.assignments().get(0).batchId(), result.assignments().get(1).batchId());

        ArgumentCaptor<AssignDriverInput> captor = ArgumentCaptor.forClass(AssignDriverInput.class);
        verify(packageService, times(2)).assignDriver(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(i -> i.driverId().equals(DRIVER_ID)));
        assertTrue(captor.getAllValues().stream().allMatch(i -> i.objectionDeadline().equals(DEADLINE)));
        assertTrue(captor.getAllValues().stream().allMatch(i -> i.acceptedAt().equals(NOW)));
    }

    @Test
    void bulkAssignValidatesAllPackagesBeforeAnyAssignment() {
        var valid = pkg("11111111-1111-1111-1111-111111111111", "CAV-1");
        when(packageRepo.findAllById(List.of(valid.getId(), UUID.fromString("33333333-3333-3333-3333-333333333333"))))
                .thenReturn(List.of(valid));

        var ex = assertThrows(BusinessValidationException.class, () -> service.assignPackagesToDriver(
                List.of(valid.getId(), UUID.fromString("33333333-3333-3333-3333-333333333333")),
                DRIVER_ID, WORKER_ID, TRIP_ID, null
        ));

        assertTrue(ex.getMessage().contains("Package not found"));
        verifyNoInteractions(packageService);
    }

    @Test
    void bulkAssignRejectsTerminalPackagesBeforeAnyAssignment() {
        var delivered = pkg("11111111-1111-1111-1111-111111111111", "CAV-1");
        delivered.setStatus(PackageStatus.DELIVERED);
        when(packageRepo.findAllById(List.of(delivered.getId()))).thenReturn(List.of(delivered));

        assertThrows(BusinessValidationException.class, () -> service.assignPackagesToDriver(
                List.of(delivered.getId()), DRIVER_ID, WORKER_ID, TRIP_ID, null
        ));

        verifyNoInteractions(packageService);
    }

    @Test
    void objectWithinWindowFlagsDisputeKeepsCustodyAndNotifiesAssigner() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var pkg = pkg(packageId.toString(), "CAV-1");
        pkg.setStatus(PackageStatus.ASSIGNED_DRIVER);
        var assignment = assignment(packageId, NOW, null, null);
        when(assignmentRepo.findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(assignment));
        when(packageRepo.findById(packageId)).thenReturn(Optional.of(pkg));
        when(assignmentRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(packageService.getPackageById(packageId)).thenReturn(mock(PackageResponse.class));

        var result = service.objectToAssignments(
                List.of(packageId), DRIVER_ID, "not physically received"
        );

        assertEquals(1, result.assignments().size());
        assertEquals(NOW, result.assignments().get(0).objectedAt());
        assertEquals("not physically received", result.assignments().get(0).objectionReason());
        assertEquals(PackageStatus.ASSIGNED_DRIVER, pkg.getStatus());
        verify(noticeService).notifyAssignmentObjected(pkg, assignment, DRIVER_ID, WORKER_ID, "not physically received");
        verify(packageService, never()).updateStatus(any());
    }

    @Test
    void objectAfterWindowIsRejected() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var assignment = assignment(packageId, NOW.minusSeconds(11 * 60), null, null);
        when(assignmentRepo.findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(assignment));

        var ex = assertThrows(BusinessValidationException.class, () -> service.objectToAssignments(
                List.of(packageId), DRIVER_ID, "late"
        ));

        assertTrue(ex.getMessage().contains("Objection window has expired"));
        verifyNoInteractions(noticeService);
    }

    @Test
    void objectByDriverWhoWasNotAssignedIsRejected() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(assignmentRepo.findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.empty());

        assertThrows(BusinessValidationException.class, () -> service.objectToAssignments(
                List.of(packageId), DRIVER_ID, "not mine"
        ));

        verifyNoInteractions(noticeService);
    }

    @Test
    void repeatedObjectionIsIdempotentAndDoesNotDuplicateNotice() {
        var packageId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var assignment = assignment(packageId, NOW, NOW, "first objection");
        when(assignmentRepo.findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(packageId, DRIVER_ID))
                .thenReturn(Optional.of(assignment));
        when(packageService.getPackageById(packageId)).thenReturn(mock(PackageResponse.class));

        var result = service.objectToAssignments(List.of(packageId), DRIVER_ID, "duplicate");

        assertFalse(result.assignments().isEmpty());
        assertEquals("first objection", result.assignments().get(0).objectionReason());
        verify(noticeService, never()).notifyAssignmentObjected(any(), any(), any(), any(), any());
    }

    private static PackageEntity pkg(String id, String trackingCode) {
        return PackageEntity.builder()
                .id(UUID.fromString(id))
                .trackingCode(trackingCode)
                .deliveryType(DeliveryType.FIXED_ROUTE)
                .status(PackageStatus.ORIGIN_OFFICE)
                .creatorId(WORKER_ID)
                .packageOrigin(PackageOrigin.FLEET)
                .createdAt(NOW.minusSeconds(300))
                .updatedAt(NOW.minusSeconds(300))
                .build();
    }

    private static PackageAssignmentEntity assignment(UUID packageId, Instant assignedAt,
                                                       Instant objectedAt, String reason) {
        return PackageAssignmentEntity.builder()
                .id(UUID.randomUUID())
                .packageId(packageId)
                .driverId(DRIVER_ID)
                .assignedBy(WORKER_ID)
                .assignedAt(assignedAt)
                .objectionDeadline(assignedAt.plusSeconds(10 * 60))
                .acceptedAt(assignedAt)
                .objectedAt(objectedAt)
                .objectionReason(reason)
                .build();
    }
}
