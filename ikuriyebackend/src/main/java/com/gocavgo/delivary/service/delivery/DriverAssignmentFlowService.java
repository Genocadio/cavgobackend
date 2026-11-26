package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.input.AssignDriverInput;
import com.gocavgo.delivary.dto.delivery.output.BulkDriverAssignmentResult;
import com.gocavgo.delivary.dto.delivery.output.DriverAssignmentResponse;
import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.PackageAssignmentJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.service.notification.NoticeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Office-to-driver assignment flow.
 *
 * <p>The driver does not accept these assignments. Custody and status move at assignment time,
 * and the driver has a bounded ten-minute window to flag a package as "not received". An
 * objection keeps custody with the driver and raises a notice for the assigning worker.</p>
 */
@Service
@RequiredArgsConstructor
public class DriverAssignmentFlowService {

    public static final int OBJECTION_WINDOW_MINUTES = 10;

    private final PackageJpaRepository packageRepo;
    private final PackageService packageService;
    private final PackageAssignmentJpaRepository assignmentRepo;
    private final NoticeService noticeService;
    private final Clock clock;

    @Transactional
    public BulkDriverAssignmentResult assignPackagesToDriver(List<UUID> packageIds,
                                                             Long driverId,
                                                             Long assignedBy,
                                                             Long tripId,
                                                             String notes) {
        var uniqueIds = distinct(packageIds);
        if (uniqueIds.isEmpty()) {
            throw new BusinessValidationException("At least one package is required");
        }

        var found = packageRepo.findAllById(uniqueIds);
        var foundIds = found.stream().map(PackageEntity::getId).collect(java.util.stream.Collectors.toSet());
        for (var packageId : uniqueIds) {
            if (!foundIds.contains(packageId)) {
                throw new BusinessValidationException("Package not found: " + packageId);
            }
        }
        for (var pkg : found) {
            if (pkg.getStatus() == PackageStatus.DELIVERED
                    || pkg.getStatus() == PackageStatus.COMPLETED
                    || pkg.getStatus() == PackageStatus.CANCELLED) {
                throw new BusinessValidationException(
                        "Cannot assign a driver to a package with status: " + pkg.getStatus());
            }
        }

        var now = clock.instant();
        var deadline = now.plusSeconds(OBJECTION_WINDOW_MINUTES * 60L);
        var batchId = UUID.randomUUID();
        var packages = new ArrayList<com.gocavgo.delivary.dto.delivery.output.PackageResponse>();
        var assignments = new ArrayList<DriverAssignmentResponse>();

        for (var packageId : uniqueIds) {
            var assignedPackage = packageService.assignDriver(new AssignDriverInput(
                    packageId,
                    driverId,
                    assignedBy,
                    notes,
                    tripId,
                    batchId,
                    deadline,
                    now
            ));
            packages.add(assignedPackage);

            // assignDriver persists the assignment and includes it in the refreshed
            // package response. Fall back to the known values if a caller returns
            // a slimmer projection, so the bulk result always satisfies the SDL.
            var persisted = assignedPackage == null || assignedPackage.driverAssignments() == null
                    ? java.util.Optional.<DriverAssignmentResponse>empty()
                    : assignedPackage.driverAssignments().stream()
                            .filter(a -> driverId.equals(a.driverId()))
                            .findFirst();
            assignments.add(persisted.orElseGet(() -> new DriverAssignmentResponse(
                    UUID.randomUUID(),
                    packageId,
                    driverId,
                    assignedBy,
                    now,
                    now,
                    deadline,
                    null,
                    null,
                    notes,
                    batchId
            )));
        }

        return new BulkDriverAssignmentResult(List.copyOf(packages), List.copyOf(assignments));
    }

    @Transactional
    public BulkDriverAssignmentResult objectToAssignments(List<UUID> packageIds,
                                                          Long driverId,
                                                          String reason) {
        var uniqueIds = distinct(packageIds);
        if (uniqueIds.isEmpty()) {
            throw new BusinessValidationException("At least one package is required");
        }

        var now = clock.instant();
        var packages = new ArrayList<com.gocavgo.delivary.dto.delivery.output.PackageResponse>();
        var assignments = new ArrayList<DriverAssignmentResponse>();

        for (var packageId : uniqueIds) {
            var assignment = assignmentRepo.findFirstByPackageIdAndDriverIdOrderByAssignedAtDesc(packageId, driverId)
                    .orElseThrow(() -> new BusinessValidationException(
                            "Package " + packageId + " is not assigned to driver " + driverId));
            var deadline = assignment.getObjectionDeadline() != null
                    ? assignment.getObjectionDeadline()
                    : assignment.getAssignedAt().plusSeconds(OBJECTION_WINDOW_MINUTES * 60L);

            if (assignment.getObjectedAt() != null) {
                assignments.add(DriverAssignmentResponse.from(assignment));
                packages.add(packageService.getPackageById(packageId));
                continue;
            }
            if (now.isAfter(deadline)) {
                throw new BusinessValidationException("Objection window has expired for package " + packageId);
            }

            assignment.setObjectedAt(now);
            assignment.setObjectionReason(reason);
            assignmentRepo.save(assignment);

            var pkg = packageRepo.findById(packageId)
                    .orElseThrow(() -> new BusinessValidationException("Package not found: " + packageId));
            noticeService.notifyAssignmentObjected(
                    pkg,
                    assignment,
                    driverId,
                    assignment.getAssignedBy(),
                    reason
            );

            assignments.add(DriverAssignmentResponse.from(assignment));
            packages.add(packageService.getPackageById(packageId));
        }

        return new BulkDriverAssignmentResult(List.copyOf(packages), List.copyOf(assignments));
    }

    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null) return List.of();
        return new LinkedHashSet<>(ids).stream().toList();
    }
}
