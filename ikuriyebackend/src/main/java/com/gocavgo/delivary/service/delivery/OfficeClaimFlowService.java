package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.output.BulkOfficeClaimResult;
import com.gocavgo.delivary.dto.delivery.output.DeliveryPackagePage;
import com.gocavgo.delivary.dto.delivery.output.OfficeClaimResponse;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.LocationType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.OfficeClaimJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.service.notification.NoticeService;
import com.gocavgo.delivary.service.transfer.TransferService;
import com.gocavgo.delivary.service.user.UserService;
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
 * Destination-office intake flow.
 *
 * <p>A worker at the package's destination office can take custody directly
 * from a driver, without waiting for the driver to create a transfer. The
 * driver can report the handoff as "not delivered" during a bounded window;
 * custody stays with the office while the dispute is flagged.</p>
 */
@Service
@RequiredArgsConstructor
public class OfficeClaimFlowService {

    public static final int DISPUTE_WINDOW_MINUTES = 10;

    private static final List<PackageStatus> CLAIMABLE_STATUSES = List.of(
            PackageStatus.ASSIGNED_DRIVER,
            PackageStatus.PICKED_UP,
            PackageStatus.IN_TRANSIT,
            PackageStatus.DESTINATION_OFFICE
    );

    private final PackageJpaRepository packageRepo;
    private final PackageLocationJpaRepository packageLocationRepo;
    private final PackageCustodianJpaRepository custodianRepo;
    private final OfficeClaimJpaRepository claimRepo;
    private final PackageService packageService;
    private final UserService userService;
    private final NoticeService noticeService;
    private final TransferService transferService;
    private final Clock clock;

    /**
     * Packages whose destination office matches the worker's office and which
     * are still driver-held. This is the worker's "incoming" intake queue.
     */
    @Transactional(readOnly = true)
    public DeliveryPackagePage getIncomingPackagesForOffice(Long workerId, UUID officeLocationId,
                                                            int page, int size) {
        var officeIds = resolveOfficeIds(workerId, officeLocationId);
        var requestedSize = size > 0 ? size : 20;
        var currentPage = Math.max(page, 0);

        var packageIds = packageLocationRepo.findPackageIdsByTypeAndOfficeLocationIdIn(
                LocationType.DESTINATION, officeIds);
        var packages = packageRepo.findAllById(packageIds).stream()
                .filter(p -> CLAIMABLE_STATUSES.contains(p.getStatus()))
                .sorted(java.util.Comparator.comparing(
                        PackageEntity::getUpdatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();

        var totalCount = packages.size();
        var from = Math.min(currentPage * requestedSize, totalCount);
        var to = Math.min(from + requestedSize, totalCount);
        var items = packages.subList(from, to).stream()
                .map(p -> packageService.getPackageById(p.getId()))
                .toList();

        return new DeliveryPackagePage(
                items,
                totalCount,
                (int) Math.ceil(totalCount / (double) requestedSize),
                currentPage
        );
    }

    /**
     * Bulk direct claim by a destination office worker. Every package must be
     * destined for the worker's office and currently held by a driver.
     */
    @Transactional
    public BulkOfficeClaimResult claimPackagesFromDrivers(List<UUID> packageIds,
                                                          Long workerId,
                                                          UUID officeLocationId,
                                                          String notes) {
        var uniqueIds = distinct(packageIds);
        if (uniqueIds.isEmpty()) {
            throw new BusinessValidationException("At least one package is required");
        }

        var officeIds = resolveOfficeIds(workerId, officeLocationId);
        var targetOfficeId = officeLocationId != null ? officeLocationId : officeIds.iterator().next();

        var found = packageRepo.findAllById(uniqueIds);
        var foundIds = found.stream().map(PackageEntity::getId).collect(java.util.stream.Collectors.toSet());
        for (var packageId : uniqueIds) {
            if (!foundIds.contains(packageId)) {
                throw new BusinessValidationException("Package not found: " + packageId);
            }
        }
        for (var pkg : found) {
            if (!CLAIMABLE_STATUSES.contains(pkg.getStatus())) {
                throw new BusinessValidationException(
                        "Cannot claim a package with status: " + pkg.getStatus());
            }
        }

        var destinationIds = packageLocationRepo
                .findByTypeAndOfficeLocationIdInAndPackageIdIn(
                        LocationType.DESTINATION, officeIds, uniqueIds)
                .stream()
                .map(location -> location.getPackageId())
                .collect(java.util.stream.Collectors.toSet());
        for (var packageId : uniqueIds) {
            if (!destinationIds.contains(packageId)) {
                throw new BusinessValidationException(
                        "Package " + packageId + " is not destined for this worker's destination office");
            }
        }

        // Validate custody before mutating anything so a bad package cannot leave
        // the rest of the batch half-claimed.
        var driverByPackage = new java.util.HashMap<UUID, Long>();
        for (var packageId : uniqueIds) {
            var custodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(packageId)
                    .orElseThrow(() -> new BusinessValidationException(
                            "Package " + packageId + " has no current custodian"));
            if (custodian.getRole() != CustodianRole.DRIVER) {
                throw new BusinessValidationException(
                        "Package " + packageId + " is not currently held by a driver");
            }
            driverByPackage.put(packageId, custodian.getUserId());
        }

        // A driver-created transfer is superseded when the office claims first.
        transferService.cancelOpenTransfersForPackages(uniqueIds, workerId);

        var now = clock.instant();
        var deadline = now.plusSeconds(DISPUTE_WINDOW_MINUTES * 60L);
        var packages = new ArrayList<PackageResponse>();
        var claims = new ArrayList<OfficeClaimResponse>();

        for (var packageId : uniqueIds) {
            var driverId = driverByPackage.get(packageId);
            var claimed = packageService.claimFromDriver(packageId, workerId, targetOfficeId);
            packages.add(claimed);

            var claim = claimRepo.save(OfficeClaimEntity.builder()
                    .id(UUID.randomUUID())
                    .packageId(packageId)
                    .driverId(driverId)
                    .claimedBy(workerId)
                    .officeLocationId(targetOfficeId)
                    .claimedAt(now)
                    .disputeDeadline(deadline)
                    .notes(notes)
                    .build());

            var pkg = found.stream()
                    .filter(p -> packageId.equals(p.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessValidationException("Package not found: " + packageId));
            noticeService.notifyOfficeClaimCreated(pkg, claim, workerId, driverId);
            claims.add(OfficeClaimResponse.from(claim));
        }

        return new BulkOfficeClaimResult(List.copyOf(packages), List.copyOf(claims));
    }

    /**
     * Driver contests one or more office claims as "not delivered". Custody is
     * intentionally unchanged; the claim is flagged and the worker is notified.
     */
    @Transactional
    public BulkOfficeClaimResult disputeClaims(List<UUID> packageIds, Long driverId, String reason) {
        var uniqueIds = distinct(packageIds);
        if (uniqueIds.isEmpty()) {
            throw new BusinessValidationException("At least one package is required");
        }

        var now = clock.instant();
        var packages = new ArrayList<PackageResponse>();
        var claims = new ArrayList<OfficeClaimResponse>();

        for (var packageId : uniqueIds) {
            var claim = claimRepo.findFirstByPackageIdAndDriverIdOrderByClaimedAtDesc(packageId, driverId)
                    .orElseThrow(() -> new BusinessValidationException(
                            "Package " + packageId + " was not claimed from driver " + driverId));
            var deadline = claim.getDisputeDeadline() != null
                    ? claim.getDisputeDeadline()
                    : claim.getClaimedAt().plusSeconds(DISPUTE_WINDOW_MINUTES * 60L);

            if (claim.getDisputedAt() != null) {
                claims.add(OfficeClaimResponse.from(claim));
                packages.add(packageService.getPackageById(packageId));
                continue;
            }
            if (now.isAfter(deadline)) {
                throw new BusinessValidationException("Dispute window has expired for package " + packageId);
            }

            claim.setDisputedAt(now);
            claim.setDisputeReason(reason);
            var saved = claimRepo.save(claim);

            var pkg = packageRepo.findById(packageId)
                    .orElseThrow(() -> new BusinessValidationException("Package not found: " + packageId));
            noticeService.notifyOfficeClaimDisputed(
                    pkg, saved, driverId, saved.getClaimedBy(), reason);

            claims.add(OfficeClaimResponse.from(saved));
            packages.add(packageService.getPackageById(packageId));
        }

        return new BulkOfficeClaimResult(List.copyOf(packages), List.copyOf(claims));
    }

    private List<UUID> resolveOfficeIds(Long workerId, UUID explicitOfficeId) {
        var officeIds = new LinkedHashSet<UUID>();
        if (explicitOfficeId != null) {
            officeIds.add(explicitOfficeId);
        }
        var workerOfficeId = userService.getCompanyIdForUser(workerId);
        if (workerOfficeId != null) {
            officeIds.add(workerOfficeId);
        }
        if (officeIds.isEmpty()) {
            throw new BusinessValidationException("Worker is not assigned to an office");
        }
        return List.copyOf(officeIds);
    }

    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null) return List.of();
        return new LinkedHashSet<>(ids).stream().toList();
    }
}
