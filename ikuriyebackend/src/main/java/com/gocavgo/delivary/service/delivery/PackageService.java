package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.security.SecurityUtils;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.enums.delivery.DeliveryType;
import com.gocavgo.delivary.enums.delivery.PackageEventType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.SortOrder;
import com.gocavgo.delivary.dto.delivery.input.AssignDriverInput;
import com.gocavgo.delivary.dto.delivery.input.AssignPackageCompanyInput;
import com.gocavgo.delivary.dto.delivery.input.AssignPackageTripInput;
import com.gocavgo.delivary.dto.delivery.input.CreatePackageInput;
import com.gocavgo.delivary.dto.delivery.input.UpdatePackageStatusInput;
import com.gocavgo.delivary.dto.delivery.output.AcceptOfferResponse;
import com.gocavgo.delivary.dto.delivery.output.DeliveryCodeResult;
import com.gocavgo.delivary.dto.delivery.output.DeliveryPackagePage;
import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.delivery.output.TransferAcceptResult;
import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import com.gocavgo.delivary.entity.delivery.PackageCustodyEntity;
import com.gocavgo.delivary.entity.delivery.PackageDetailEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.delivery.PackageEventEntity;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import com.gocavgo.delivary.entity.delivery.PackagePersonEntity;
import com.gocavgo.delivary.entity.delivery.DeliveryCodeEntity;
import com.gocavgo.delivary.repository.delivery.PackageAssignmentJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCustodyJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageDetailJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageEventJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageLocationJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackagePersonJpaRepository;
import com.gocavgo.delivary.repository.delivery.DeliveryCodeJpaRepository;
import com.gocavgo.delivary.mapper.delivery.DeliveryMapper;
import com.gocavgo.delivary.repository.user.UserRepository;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.service.notification.NoticeEventMapper;
import com.gocavgo.delivary.service.notification.NoticeService;
import com.gocavgo.delivary.service.storage.StorageService;
import com.gocavgo.delivary.service.subscription.PackageTransferPublisher;
import com.gocavgo.delivary.service.transfer.TransferService;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.dto.transfer.output.TransferResponse;
import com.gocavgo.delivary.entity.transfer.TransferEntity;
import com.gocavgo.delivary.entity.transfer.TransferPackageEntity;
import com.gocavgo.delivary.repository.transfer.TransferJpaRepository;
import com.gocavgo.delivary.repository.transfer.TransferPackageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PackageService {

    private static final Logger log = LoggerFactory.getLogger(PackageService.class);

    private final PackageValidationService validationService;
    private final PackageJpaRepository packageRepo;
    private final PackagePersonJpaRepository personRepo;
    private final PackageLocationJpaRepository locationRepo;
    private final PackageDetailJpaRepository detailRepo;
    private final PackageMediaJpaRepository mediaRepo;
    private final PackageAssignmentJpaRepository assignmentRepo;
    private final PackageEventJpaRepository eventRepo;
    private final PackageCustodyJpaRepository custodyRepo;
    private final PackageCustodianJpaRepository custodianRepo;
    private final DeliveryCodeJpaRepository deliveryCodeRepo;
    private final UserRepository userRepository;
    private final NoticeService noticeService;
    private final PackageTransferPublisher packageTransferPublisher;
    private final TransferService transferService;
    private final TransferJpaRepository transferRepo;
    private final TransferPackageJpaRepository transferPackageRepo;
    private final DeliveryMapper deliveryMapper;
    private final StorageService storageService;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Transactional
    public PackageCreationResponse createPackage(Long creatorId, Role role, UUID companyId, CreatePackageInput input) {
        validationService.validateCreator(creatorId, role);

        PackageStatus initialStatus;
        PackageEventType initialEventType;
        String initialEventDesc;

        switch (role) {
            case DRIVER -> {
                initialStatus = PackageStatus.ACCEPTED;
                initialEventType = PackageEventType.ACCEPTED;
                initialEventDesc = "Package auto-accepted by driver";
            }
            case WORKER -> {
                initialStatus = PackageStatus.ORIGIN_OFFICE;
                initialEventType = PackageEventType.ORIGIN_ARRIVED;
                initialEventDesc = "Package arrived at origin office";
            }
            default -> {
                // CUSTOMER — package enters the pool, no custodian yet
                initialStatus = PackageStatus.CREATED;
                initialEventType = PackageEventType.CREATED;
                initialEventDesc = "Package created";
            }
        }

        var trackingCode = generateTrackingCode();
        var pkg = PackageEntity.builder()
                .trackingCode(trackingCode)
                .deliveryType(input.deliveryType())
                .status(initialStatus)
                .creatorId(creatorId)
                .companyId(companyId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        packageRepo.save(pkg);

        // Only WORKER and DRIVER become the first custodian — CUSTOMER is not a custodian
        if (role == Role.WORKER) {
            saveCustodian(pkg.getId(), creatorId, CustodianRole.WORKER);
        } else if (role == Role.DRIVER) {
            saveCustodian(pkg.getId(), creatorId, CustodianRole.DRIVER);
        }

        savePerson(pkg.getId(), input.sender());
        savePerson(pkg.getId(), input.receiver());

        // Every office id supplied on a location must reference a valid office
        if (input.origin() != null) {
            validationService.validateOffice(input.origin().officeId());
        }
        if (input.destination() != null) {
            validationService.validateOffice(input.destination().officeId());
        }
        saveLocation(pkg.getId(), input.origin());
        saveLocation(pkg.getId(), input.destination());

        if (input.details() != null) {
            var det = input.details();
            var detail = PackageDetailEntity.builder()
                    .packageId(pkg.getId())
                    .category(det.category())
                    .description(det.description())
                    .fragile(det.fragile())
                    .weight(det.weight())
                    .length(det.length())
                    .width(det.width())
                    .height(det.height())
                    .declaredValue(det.declaredValue())
                    .build();
            detailRepo.save(detail);

            if (det.media() != null) {
                for (var m : det.media()) {
                    // Link pre-uploaded media to this package by mediaId
                    try {
                        var mediaId = java.util.UUID.fromString(m.mediaId());
                        var existingMedia = mediaRepo.findById(mediaId).orElse(null);
                        if (existingMedia != null) {
                            existingMedia.setPackageId(pkg.getId());
                            mediaRepo.save(existingMedia);
                        } else {
                            log.warn("Media not found for mediaId={}, skipping", m.mediaId());
                        }
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid mediaId format: {}, skipping", m.mediaId());
                    }
                }
            }
        }

        if (role == Role.DRIVER) {
            saveCustody(pkg.getId(), "SENDER", CustodianRole.DRIVER.name(), "Auto-accepted by driver at creation");
        }

        saveEvent(pkg.getId(), initialEventType, creatorId, initialEventDesc);

        // === Optional: auto-create a transfer with this package ===
        TransferResponse transferResponse = null;
        if (input.transferRuleType() != null) {
            var createTransferInput = new CreateTransferInput(
                    List.of(pkg.getId()),
                    input.transferRuleType(),
                    null, // acceptorType defaults to BOTH in TransferService
                    input.transferMatchCompanyId(),
                    input.transferMatchUserId()
            );
            transferResponse = transferService.createTransfer(creatorId, createTransferInput);
        }

        // Notify: package created
        noticeService.notifyPackageEvent(pkg, NoticeEventMapper.fromPackageStatus(pkg.getStatus()), creatorId, null);

        var response = new PackageCreationResponse(toResponse(pkg, true), transferResponse);

        // Publish to real-time subscription subscribers if a transfer was auto-created
        if (transferResponse != null) {
            packageTransferPublisher.publish(response);
        }

        return response;
    }

    /**
     * Pre-validates that ALL packages in a transfer can be handed over:
     * they must not be in a terminal status (COMPLETED / CANCELLED).
     * CREATED packages advance to ACCEPTED; in-flight packages keep their
     * current status and simply change custodian. Throws at the first invalid
     * package — fail-fast before any package is mutated.
     */
    private void validateAllPackagesAcceptable(List<TransferPackageEntity> transferPackages, Long actorId) {
        for (var tp : transferPackages) {
            var pkg = packageRepo.findById(tp.getPackageId())
                    .orElseThrow(() -> new RuntimeException("Package not found: " + tp.getPackageId()));
            if (pkg.getStatus() == PackageStatus.COMPLETED || pkg.getStatus() == PackageStatus.CANCELLED) {
                throw new RuntimeException("Cannot accept a package with status: " + pkg.getStatus());
            }
        }
    }

    /**
     * Core logic: accept a single package — update status (CREATED → ACCEPTED;
     * in-flight packages keep their status and swap custodian), add custodian,
     * record custody and event.
     */
    private AcceptOfferResponse acceptSinglePackage(UUID packageId, Long actorId, CustodianRole custodianRole) {
        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found: " + packageId));

        // Re-validate right before mutation — guards against race conditions
        // where the package status was changed (e.g. cancelled) after
        // pre-validation but before the actual status update.
        if (pkg.getStatus() == PackageStatus.COMPLETED || pkg.getStatus() == PackageStatus.CANCELLED) {
            throw new RuntimeException("Cannot accept a package with status: " + pkg.getStatus());
        }

        var previousStatus = pkg.getStatus();
        if (previousStatus == PackageStatus.CREATED) {
            validationService.validateTransition(pkg.getStatus(), PackageStatus.ACCEPTED, pkg.getDeliveryType());
            pkg.setStatus(PackageStatus.ACCEPTED);
        }
        pkg.setUpdatedAt(Instant.now());
        packageRepo.save(pkg);

        // Notify previous custodian (if any) that they've been replaced
        custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId())
                .ifPresent(prev -> {
                    if (!prev.getUserId().equals(actorId)) {
                        noticeService.notifyPackageCustodianRemoved(pkg, prev.getUserId(), actorId);
                    }
                });

        saveCustodian(pkg.getId(), actorId, custodianRole);

        // Notify: package accepted + custodian assigned
        noticeService.notifyPackageEvent(pkg, NoticeEventMapper.fromPackageStatus(PackageStatus.ACCEPTED), actorId, previousStatus);
        noticeService.notifyPackageEvent(pkg, NoticeEventType.PACKAGE_CUSTODIAN_ASSIGNED, actorId, previousStatus);

        var currentCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId());
        var fromEntity = currentCustodian
                .map(c -> c.getRole().name())
                .orElse("SENDER");
        saveCustody(pkg.getId(), fromEntity, custodianRole.name(),
                "Accepted by " + custodianRole.name().toLowerCase());
        saveEvent(pkg.getId(), PackageEventType.ACCEPTED, actorId, "Package accepted");

        return new AcceptOfferResponse(toResponse(pkg, true));
    }

    @Transactional
    public TransferAcceptResult acceptPackageByTransfer(Long actorId, UUID transferId, String transferCode) {
        // Find and validate the transfer (with pessimistic lock to prevent race conditions)
        var transferEntity = transferService.getTransferEntityWithLock(transferId);

        if (transferEntity.getRuleType() == TransferRuleType.CONFIRM) {
            throw new RuntimeException("CONFIRM transfers must use acceptTransfer (CONFIRM mode) then confirmTransfer");
        }
        if (transferEntity.getStatus() != TransferStatus.PENDING) {
            throw new RuntimeException("Transfer is not open for acceptance (status=" + transferEntity.getStatus() + ")");
        }

        // If SECURE, verify the transfer code
        if (transferEntity.getRuleType() == TransferRuleType.SECURE) {
            if (transferCode == null || transferCode.isBlank()) {
                throw new RuntimeException("Transfer code is required for SECURE transfers");
            }
            var hash = hashCode(transferCode);
            if (!hash.equals(transferEntity.getTransferCodeHash())) {
                throw new RuntimeException("Invalid transfer code");
            }
        }

        // Determine role from the JWT token
        var actorRole = SecurityUtils.getCurrentUserRole();
        validationService.validateAcceptor(actorId, actorRole);

        // Validate that the acceptor's role matches the transfer's acceptorType
        validationService.validateAcceptorType(transferEntity.getAcceptorType(), actorRole, transferId);

        var custodianRole = actorRole == Role.DRIVER ? CustodianRole.DRIVER : CustodianRole.WORKER;

        // Get all packages in the transfer
        var transferPackages = transferPackageRepo.findByTransferId(transferId);
        if (transferPackages.isEmpty()) {
            throw new RuntimeException("Transfer has no packages");
        }

        // ★ Pre-validate ALL packages before accepting any (fail-fast)
        validateAllPackagesAcceptable(transferPackages, actorId);

        // Accept all packages
        var acceptResults = new ArrayList<AcceptOfferResponse>();
        for (var tp : transferPackages) {
            acceptResults.add(acceptSinglePackage(tp.getPackageId(), actorId, custodianRole));
        }

        // Complete the transfer
        transferEntity.setStatus(TransferStatus.DONE);
        transferEntity.setUpdatedAt(Instant.now());
        transferService.saveTransferEntity(transferEntity);

        // Notify: transfer completed — the acceptor already knows they accepted,
        // so only the transfer creator is informed their transfer is done.
        noticeService.notifyTransferEvent(transferEntity,
                NoticeEventMapper.fromTransferStatus(TransferStatus.DONE), actorId, TransferStatus.PENDING);

        var allPackages = transferPackageRepo.findByTransferId(transferId);
        var transferResp = transferService.toResponse(transferEntity, allPackages, null);

        return new TransferAcceptResult(transferResp, acceptResults);
    }

    /**
     * Accepts all packages in a transfer as part of the CONFIRM flow.
     * Called by TransferService.confirmTransfer() when the owner confirms.
     * The requestor (who previously called requestTransfer) becomes the custodian.
     */
    @Transactional
    public List<PackageResponse> acceptPackagesForTransferConfirmation(Long requestorId, UUID transferId) {
        log.info("acceptPackagesForTransferConfirmation: requestorId={}, transferId={}", requestorId, transferId);

        // Determine role from the JWT token
        var requestorRole = SecurityUtils.getCurrentUserRole();
        validationService.validateAcceptor(requestorId, requestorRole);

        // Validate that the acceptor's role matches the transfer's acceptorType
        // We need to load the transfer to check acceptorType
        var transferEntity = transferService.getTransferEntityById(transferId);
        validationService.validateAcceptorType(transferEntity.getAcceptorType(), requestorRole, transferId);

        var custodianRole = requestorRole == Role.DRIVER ? CustodianRole.DRIVER : CustodianRole.WORKER;

        // Get all packages in the transfer
        var transferPackages = transferPackageRepo.findByTransferId(transferId);
        if (transferPackages.isEmpty()) {
            throw new RuntimeException("Transfer has no packages");
        }

        // ★ Pre-validate ALL packages before accepting any (fail-fast)
        validateAllPackagesAcceptable(transferPackages, requestorId);

        // Accept all packages
        var results = new ArrayList<PackageResponse>();
        for (var tp : transferPackages) {
            acceptSinglePackage(tp.getPackageId(), requestorId, custodianRole);
            var pkg = packageRepo.findById(tp.getPackageId()).orElseThrow();
            results.add(toResponse(pkg, true));
        }

        return results;
    }

    /**
     * Validates the actor is an active WORKER or DRIVER and returns their role.
     * Used by TransferService for CONFIRM mode validation.
     */
    @Transactional(readOnly = true)
    public com.gocavgo.delivary.enums.user.Role validateAndGetAcceptorRole(Long actorId) {
        var role = SecurityUtils.getCurrentUserRole();
        validationService.validateAcceptor(actorId, role);
        return role;
    }

    /**
     * Initiates delivery for a package: transitions it to PENDING_CONFIRMATION,
     * generates a one-time delivery code, and publishes that code to the
     * package's people (sender/receiver) and custodians via their notice feed.
     * Only the current custodian (an active WORKER/DRIVER) can initiate.
     */
    @Transactional
    public DeliveryCodeResult initiateDelivery(Long actorId, UUID packageId) {
        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found: " + packageId));

        var role = SecurityUtils.getCurrentUserRole();
        validationService.validateAcceptor(actorId, role);
        validateCurrentCustodian(pkg.getId(), actorId);
        validationService.validateTransition(pkg.getStatus(), PackageStatus.PENDING_CONFIRMATION, pkg.getDeliveryType());

        var previousStatus = pkg.getStatus();
        var rawCode = generateDeliveryCode();
        saveDeliveryCode(pkg.getId(), rawCode);

        applyTransition(pkg, actorId, PackageStatus.PENDING_CONFIRMATION, "Delivery initiated");

        // Publish the status change + delivery code to the sender and receiver ONLY via
        // notices (applyTransition skips its generic notice for this status to avoid a
        // duplicate). Custodians never receive this notice — they initiated it.
        // The client picks it up through Supabase Realtime on notice_viewers → notice payload.
        noticeService.notifyDeliveryCodeIssued(pkg, rawCode, actorId, previousStatus);

        log.info("initiateDelivery: package={}, delivery code issued", packageId);
        return new DeliveryCodeResult(toResponse(pkg, true), rawCode);
    }

    /**
     * Confirms delivery of a package that is awaiting confirmation. The caller
     * must present the delivery code and must be the sender, the receiver, or
     * the custodian who initiated the delivery. Transitions to DELIVERED.
     */
    @Transactional
    public PackageResponse confirmDelivery(Long actorId, UUID packageId, String deliveryCode) {
        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found: " + packageId));

        if (pkg.getStatus() != PackageStatus.PENDING_CONFIRMATION) {
            throw new RuntimeException("Package is not awaiting delivery confirmation (status=" + pkg.getStatus() + ")");
        }

        validateConfirmingActor(pkg, actorId);
        verifyDeliveryCode(pkg.getId(), deliveryCode);

        return applyTransition(pkg, actorId, PackageStatus.DELIVERED, "Delivery confirmed");
    }

    /**
     * Regenerates the delivery code for a package. Only allowed while the
     * package is in PENDING_CONFIRMATION and only for the current custodian.
     * The previous code is invalidated by overwriting its hash.
     */
    @Transactional
    public DeliveryCodeResult regenerateDeliveryCode(Long actorId, UUID packageId) {
        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found: " + packageId));

        if (pkg.getStatus() != PackageStatus.PENDING_CONFIRMATION) {
            throw new RuntimeException("Delivery code can only be regenerated while the package is PENDING_CONFIRMATION (status=" + pkg.getStatus() + ")");
        }

        var role = SecurityUtils.getCurrentUserRole();
        validationService.validateAcceptor(actorId, role);
        validateCurrentCustodian(pkg.getId(), actorId);

        var previousStatus = pkg.getStatus();
        var rawCode = generateDeliveryCode();
        saveDeliveryCode(pkg.getId(), rawCode);
        noticeService.notifyDeliveryCodeIssued(pkg, rawCode, actorId, previousStatus);

        log.info("regenerateDeliveryCode: package={}, delivery code reissued", packageId);
        return new DeliveryCodeResult(toResponse(pkg, true), rawCode);
    }

    @Transactional
    public PackageResponse assignDriver(AssignDriverInput input) {
        var pkg = packageRepo.findById(input.packageId())
                .orElseThrow(() -> new RuntimeException("Package not found: " + input.packageId()));

        // Guard: cannot assign a driver to a package that is already completed or cancelled
        if (pkg.getStatus() == PackageStatus.COMPLETED || pkg.getStatus() == PackageStatus.CANCELLED) {
            throw new RuntimeException("Cannot assign a driver to a package with status: " + pkg.getStatus());
        }

        // The assigner must be a WORKER/ADMIN/SUPER_ADMIN or the driver assigning themselves
        var assignerRole = SecurityUtils.getCurrentUserRole();
        validationService.validateAssigner(input.assignedBy(), assignerRole);
        validationService.validateDriver(input.driverId());

        // The assigner must hold current custody (or be trusted office staff) —
        // replaces the old one-time handover-token proof.
        validateAssignerHoldsCustody(pkg, input.assignedBy());

        // For FIXED_ROUTE: ORIGIN_OFFICE → ASSIGNED_DRIVER is the valid transition
        // For OPEN_ROUTE: no status change at assign time (ACCEPTED → driver picks up via PICKED_UP)
        var previousStatus = pkg.getStatus();
        if (pkg.getDeliveryType() == DeliveryType.FIXED_ROUTE) {
            validationService.validateTransition(pkg.getStatus(), PackageStatus.ASSIGNED_DRIVER, pkg.getDeliveryType());
            pkg.setStatus(PackageStatus.ASSIGNED_DRIVER);
        }
        pkg.setUpdatedAt(Instant.now());
        packageRepo.save(pkg);

        // Notify previous custodian (if any) that they've been replaced
        custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId())
                .ifPresent(prev -> {
                    if (!prev.getUserId().equals(input.driverId())) {
                        noticeService.notifyPackageCustodianRemoved(pkg, prev.getUserId(), input.assignedBy());
                    }
                });

        saveCustodian(pkg.getId(), input.driverId(), CustodianRole.DRIVER);

        // Notify: custodian assigned + status change if FIXED_ROUTE
        noticeService.notifyPackageEvent(pkg, NoticeEventType.PACKAGE_CUSTODIAN_ASSIGNED, input.assignedBy(), previousStatus);
        if (pkg.getDeliveryType() == DeliveryType.FIXED_ROUTE) {
            noticeService.notifyPackageEvent(pkg, NoticeEventMapper.fromPackageStatus(PackageStatus.ASSIGNED_DRIVER), input.assignedBy(), previousStatus);
        }

        var assignment = PackageAssignmentEntity.builder()
                .packageId(pkg.getId())
                .driverId(input.driverId())
                .assignedBy(input.assignedBy())
                .assignedAt(Instant.now())
                .notes(input.notes())
                .build();
        assignmentRepo.save(assignment);

        // fromEntity: use the current custodian's role; if none yet, use SENDER
        var currentCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId());
        var fromEntity = currentCustodian
                .map(c -> c.getRole().name())
                .orElse("SENDER");
        saveCustody(pkg.getId(), fromEntity, CustodianRole.DRIVER.name(), "Driver assigned");
        saveEvent(pkg.getId(), PackageEventType.ASSIGNED, input.assignedBy(),
                "Driver assigned: " + input.driverId());

        return toResponse(pkg, true);
    }

    @Transactional
    public PackageResponse updateStatus(UpdatePackageStatusInput input) {
        var pkg = packageRepo.findById(input.packageId())
                .orElseThrow(() -> new RuntimeException("Package not found: " + input.packageId()));

        validationService.validateTransition(pkg.getStatus(), input.status(), pkg.getDeliveryType());

        if (input.status() == PackageStatus.PENDING_CONFIRMATION) {
            throw new RuntimeException("PENDING_CONFIRMATION must be set via the initiateDelivery mutation");
        }
        if (input.status() == PackageStatus.DELIVERED) {
            throw new RuntimeException("DELIVERED must be set via the confirmDelivery mutation with the delivery code");
        }

        // The actor must hold current custody (or be trusted office staff) —
        // replaces the old one-time handover-token proof on PICKED_UP.
        validateStatusActor(pkg, input.actorId());

        return applyTransition(pkg, input.actorId(), input.status(), input.notes());
    }

    /**
     * Shared core of every status transition: persists the new status, updates
     * the custody chain, emits the package event notice, appends the event and
     * returns the package response. Callers are responsible for validating the
     * transition and any codes/tokens beforehand.
     */
    private PackageResponse applyTransition(PackageEntity pkg, Long actorId, PackageStatus status,
                                            String notes) {
        var previousStatus = pkg.getStatus();
        pkg.setStatus(status);
        pkg.setUpdatedAt(Instant.now());
        packageRepo.save(pkg);

        updateCustodianForStatus(pkg, actorId);

        // Notify: package status change. PENDING_CONFIRMATION is excluded — its notice
        // (status change + delivery code) is sent by notifyDeliveryCodeIssued.
        if (status != PackageStatus.PENDING_CONFIRMATION) {
            noticeService.notifyPackageEvent(pkg, NoticeEventMapper.fromPackageStatus(status), actorId, previousStatus);
        }

        var eventType = mapStatusToEvent(status);
        saveEvent(pkg.getId(), eventType, actorId,
                notes != null ? notes : "Status: " + status);

        return toResponse(pkg, true);
    }

    /**
     * Updates custody to match the new package status.
     */
    private void updateCustodianForStatus(PackageEntity pkg, Long actorId) {
        var currentCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId());

        // Notify previous custodian when they are being replaced (applies in cases below)
        Runnable notifyRemoved = () -> currentCustodian.ifPresent(prev -> {
            if (!prev.getUserId().equals(actorId)) {
                noticeService.notifyPackageCustodianRemoved(pkg, prev.getUserId(), actorId);
            }
        });

        switch (pkg.getStatus()) {
            case PICKED_UP -> {
                notifyRemoved.run();
                saveCustodian(pkg.getId(), actorId, CustodianRole.DRIVER);
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.DRIVER.name(),
                            "Picked up by driver");
                }
            }
            case IN_TRANSIT -> {
                notifyRemoved.run();
                saveCustodian(pkg.getId(), actorId, CustodianRole.DRIVER);
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.DRIVER.name(),
                            "In transit");
                }
            }
            case ORIGIN_OFFICE -> {
                notifyRemoved.run();
                saveCustodian(pkg.getId(), actorId, CustodianRole.WORKER);
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.WORKER.name(),
                            "Arrived at origin office");
                }
            }
            case DESTINATION_OFFICE -> {
                notifyRemoved.run();
                saveCustodian(pkg.getId(), actorId, CustodianRole.OFFICE);
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.OFFICE.name(),
                            "Arrived at destination office");
                }
            }
            case READY_FOR_COLLECTION -> {
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.OFFICE.name(),
                            "Ready for collection");
                }
            }
            case PENDING_CONFIRMATION -> {
                // No new custodian — the package stays with the current custodian
                // while the delivery code is awaiting confirmation.
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), currentCustodian.get().getRole().name(),
                            "Delivery initiated, awaiting confirmation");
                }
            }
            case DELIVERED -> {
                // The driver completes delivery — custody passes to RECEIVER (the recipient person).
                // We record the custody transfer but do NOT add a custodian row for the receiver
                // because the receiver is tracked in package_people, not package_custodians.
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), CustodianRole.RECEIVER.name(),
                            "Delivered to receiver");
                }
            }
            case COMPLETED -> {
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), "COMPLETED",
                            "Package completed");
                }
            }
            case CANCELLED -> {
                // No new custodian on cancellation — the package is simply closed.
                // Record where it was when cancelled.
                if (currentCustodian.isPresent()) {
                    saveCustody(pkg.getId(), currentCustodian.get().getRole().name(), "CANCELLED",
                            "Package cancelled");
                } else {
                    saveCustody(pkg.getId(), "SENDER", "CANCELLED", "Package cancelled before pickup");
                }
            }
            default -> {
                // no custody change
            }
        }
    }

    @Transactional
    public PackageResponse assignPackageCompany(AssignPackageCompanyInput input) {
        var pkg = packageRepo.findById(input.packageId())
                .orElseThrow(() -> new RuntimeException("Package not found: " + input.packageId()));
        validationService.validateOffice(input.companyId());
        pkg.setCompanyId(input.companyId());
        pkg.setUpdatedAt(Instant.now());
        packageRepo.save(pkg);
        return toResponse(pkg, true);
    }

    @Transactional
    public PackageResponse assignPackageTrip(AssignPackageTripInput input) {
        var pkg = packageRepo.findById(input.packageId())
                .orElseThrow(() -> new RuntimeException("Package not found: " + input.packageId()));
        pkg.setTripId(input.tripId());
        pkg.setUpdatedAt(Instant.now());
        packageRepo.save(pkg);
        return toResponse(pkg, true);
    }

    /**
     * The actor performing a status transition must hold current custody of the
     * package, or be trusted office staff (WORKER/ADMIN/SUPER_ADMIN) acting on
     * the office's behalf. Replaces the old one-time handover-token proof.
     */
    private void validateStatusActor(PackageEntity pkg, Long actorId) {
        var isCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId())
                .map(c -> c.getUserId().equals(actorId))
                .orElse(false);
        if (isCustodian) return;

        // Role is verified from the JWT token, not the database.
        var role = SecurityUtils.getCurrentUserRole();
        if (role == Role.WORKER || role == Role.ADMIN || role == Role.SUPER_ADMIN) return;

        throw new RuntimeException("Only the current custodian or office staff can update this package's status");
    }

    /**
     * The driver-assign target check: the assigner must hold current custody of
     * the package, or be trusted office staff (WORKER/ADMIN/SUPER_ADMIN) acting
     * on the office's behalf. Replaces the old one-time handover-token proof.
     */
    private void validateAssignerHoldsCustody(PackageEntity pkg, Long assignerId) {
        // Role is verified from the JWT token, not the database.
        var role = SecurityUtils.getCurrentUserRole();
        if (role == Role.WORKER || role == Role.ADMIN || role == Role.SUPER_ADMIN) return;
        validateCurrentCustodian(pkg.getId(), assignerId);
    }

    private void verifyDeliveryCode(UUID packageId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new RuntimeException("Delivery code is required");
        }
        var code = deliveryCodeRepo.findByPackageId(packageId)
                .orElseThrow(() -> new RuntimeException("Delivery code not found for package: " + packageId));
        if (code.getUsedAt() != null) {
            throw new RuntimeException("Delivery code already used");
        }
        if (code.getExpiresAt() != null && code.getExpiresAt().isBefore(Instant.now())) {
            throw new RuntimeException("Delivery code has expired");
        }
        if (!verifyCode(rawCode, code.getCodeHash())) {
            throw new RuntimeException("Invalid delivery code");
        }
        code.setUsedAt(Instant.now());
        deliveryCodeRepo.save(code);
    }

    /**
     * Stores the delivery code hash for a package, overwriting any previous
     * code (which is thereby invalidated).
     */
    private void saveDeliveryCode(UUID packageId, String rawCode) {
        var codeHash = hashCode(rawCode);
        var existing = deliveryCodeRepo.findByPackageId(packageId);
        if (existing.isPresent()) {
            var code = existing.get();
            code.setCodeHash(codeHash);
            code.setUsedAt(null);
            code.setExpiresAt(Instant.now().plusSeconds(86400 * 7));
            deliveryCodeRepo.save(code);
        } else {
            deliveryCodeRepo.save(DeliveryCodeEntity.builder()
                    .packageId(packageId)
                    .codeHash(codeHash)
                    .expiresAt(Instant.now().plusSeconds(86400 * 7))
                    .build());
        }
    }

    private void validateCurrentCustodian(UUID packageId, Long actorId) {
        custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(packageId)
                .filter(c -> c.getUserId().equals(actorId))
                .orElseThrow(() -> new RuntimeException("Only the current custodian can perform this action"));
    }

    /**
     * Delivery can be confirmed by the sender, the receiver, or the current
     * custodian (e.g. the driver who initiated the delivery).
     */
    private void validateConfirmingActor(PackageEntity pkg, Long actorId) {
        var isCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(pkg.getId())
                .map(c -> c.getUserId().equals(actorId))
                .orElse(false);
        if (isCustodian) return;

        var isPerson = personRepo.findByPackageId(pkg.getId()).stream()
                .anyMatch(p -> p.getUserId() != null && p.getUserId().equals(actorId));
        if (isPerson) return;

        throw new RuntimeException("Only the sender, receiver, or current custodian can confirm delivery");
    }

    /**
     * Deletes a package and ALL its data: media files (from Supabase/local),
     * media DB rows, events, custody, people, locations, details, custodians,
     * assignments, delivery codes, and transfer links.
     */
    @Transactional
    public boolean deletePackage(Long actorId, UUID packageId) {
        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found: " + packageId));

        // Only creator, admin/super_admin, or current custodian can delete
        var role = SecurityUtils.getCurrentUserRole();
        boolean isCreator = pkg.getCreatorId() != null && pkg.getCreatorId().equals(actorId);
        boolean isAdmin = role == Role.ADMIN || role == Role.SUPER_ADMIN;
        boolean isCustodian = custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(packageId)
                .map(c -> c.getUserId().equals(actorId)).orElse(false);

        if (!isCreator && !isAdmin && !isCustodian) {
            throw new RuntimeException("Only the creator, admin, or current custodian can delete this package");
        }

        // 1. Delete media files from storage (Supabase or local disk)
        var mediaItems = mediaRepo.findByPackageId(packageId);
        for (var media : mediaItems) {
            storageService.deleteFile(media.getBucket(), media.getStoragePath(), media.getStorageMode());
        }

        // 2. Delete media DB rows
        mediaRepo.deleteByPackageId(packageId);

        // 3. Delete related DB rows (order matters for FK constraints)
        deliveryCodeRepo.findByPackageId(packageId).ifPresent(deliveryCodeRepo::delete);
        eventRepo.deleteByPackageId(packageId);
        custodyRepo.deleteByPackageId(packageId);
        custodianRepo.deleteByPackageId(packageId);
        personRepo.deleteByPackageId(packageId);
        locationRepo.deleteByPackageId(packageId);
        detailRepo.deleteByPackageId(packageId);
        assignmentRepo.deleteByPackageId(packageId);

        // 4. Delete transfer links (don't delete the transfer itself)
        transferPackageRepo.deleteByPackageId(packageId);

        // 5. Delete the package
        packageRepo.delete(pkg);

        log.info("Package deleted: id={}, media={}, by user={}", packageId, mediaItems.size(), actorId);
        return true;
    }

    @Transactional(readOnly = true)
    public PackageResponse getPackageById(UUID id) {
        var pkg = packageRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Package not found: " + id));
        return toResponse(pkg, true);
    }

    @Transactional(readOnly = true)
    public PackageResponse getPackageByTrackingCode(String code) {
        var pkg = packageRepo.findByTrackingCode(code)
                .orElseThrow(() -> new RuntimeException("Package not found: " + code));
        return toResponse(pkg, true);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getMyPackages(Long userId, String phone, Role role,
                                             PackageStatus statusFilter, SortOrder order,
                                             int page, int size) {
        var packageIds = new LinkedHashSet<UUID>();

        if (role == Role.CUSTOMER) {
            personRepo.findByUserId(userId).stream()
                    .map(PackagePersonEntity::getPackageId)
                    .forEach(packageIds::add);
            if (phone != null) {
                personRepo.findByPhone(phone).stream()
                        .map(PackagePersonEntity::getPackageId)
                        .forEach(packageIds::add);
            }
        } else if (role == Role.DRIVER) {
            custodianRepo.findByUserId(userId).stream()
                    .map(PackageCustodianEntity::getPackageId)
                    .forEach(packageIds::add);
        } else {
            packageRepo.findByStatus(PackageStatus.CREATED, Sort.unsorted()).stream()
                    .map(PackageEntity::getId)
                    .forEach(packageIds::add);
            custodianRepo.findByUserId(userId).stream()
                    .map(PackageCustodianEntity::getPackageId)
                    .forEach(packageIds::add);
        }

        // Also include packages from REQUESTED transfers where this user is
        // the matchUserId or the requestorId — they have a pending acceptance
        // offer and should see those packages in their list.
        addPackageIdsFromRequestedTransfers(userId, packageIds);

        var packages = packageIds.stream()
                .map(id -> packageRepo.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        return toPage(filterAndSort(packages, statusFilter, order), page, size);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getMyPackages(Long userId, String phone, Role role) {
        return getMyPackages(userId, phone, role, null, SortOrder.ASC, 0, 20);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByCreator(Long creatorId, PackageStatus statusFilter,
                                                    SortOrder order, int page, int size) {
        if (statusFilter != null) {
            var sort = order == SortOrder.DESC
                    ? Sort.by(Sort.Direction.DESC, "createdAt")
                    : Sort.by(Sort.Direction.ASC, "createdAt");
            var all = packageRepo.findByCreatorId(creatorId, sort);
            var filtered = all.stream().filter(p -> p.getStatus() == statusFilter).toList();
            return toPage(filtered, page, size);
        }
        var pageable = resolvePageRequest(order, page, size);
        return toPage(packageRepo.findByCreatorId(creatorId, pageable));
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByCreator(Long creatorId) {
        return getPackagesByCreator(creatorId, null, SortOrder.ASC, 0, 20);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByDriver(Long driverId, PackageStatus statusFilter,
                                                   SortOrder order, int page, int size) {
        var packages = custodianRepo.findByUserIdAndRole(driverId, CustodianRole.DRIVER).stream()
                .map(c -> packageRepo.findById(c.getPackageId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        return toPage(filterAndSort(packages, statusFilter, order), page, size);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByDriver(Long driverId) {
        return getPackagesByDriver(driverId, null, SortOrder.ASC, 0, 20);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByStatus(PackageStatus status, SortOrder order,
                                                   int page, int size) {
        var pageable = resolvePageRequest(order, page, size);
        return toPage(packageRepo.findByStatus(status, pageable));
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getAvailablePackages(PackageStatus statusFilter, SortOrder order,
                                                    int page, int size) {
        return getAvailablePackages(statusFilter, order, page, size, null, null, null);
    }

    /**
     * Fetch packages available for the current user, taking into account
     * transfer targeting (matchUserId, acceptorType, matchCompanyId).
     * Packages in PENDING/REQUESTED transfers that target the current user
     * are still shown so drivers/workers don't miss subscription-recovery polls.
     */
    @Transactional(readOnly = true)
    public DeliveryPackagePage getAvailablePackages(PackageStatus statusFilter, SortOrder order,
                                                    int page, int size,
                                                    Long currentUserId, Role currentRole,
                                                    java.util.UUID currentCompanyId) {
        var status = statusFilter != null ? statusFilter : PackageStatus.CREATED;
        var pageable = resolvePageRequest(order, page, size);
        var paged = packageRepo.findByStatus(status, pageable);

        // Exclude packages in PENDING/REQUESTED transfers — UNLESS the transfer
        // targets the current user (via matchUserId, acceptorType, or matchCompanyId).
        var pendingTransfers = java.util.stream.Stream.of(
                        transferRepo.findByStatus(TransferStatus.PENDING),
                        transferRepo.findByStatus(TransferStatus.REQUESTED)
                )
                .flatMap(java.util.Collection::stream)
                .toList();

        var unavailablePackageIds = pendingTransfers.stream()
                .filter(t -> !transferTargetsUser(t, currentUserId, currentRole, currentCompanyId))
                .flatMap(t -> transferPackageRepo.findPackageIdsByTransferId(t.getId()).stream())
                .collect(java.util.stream.Collectors.toSet());

        var filtered = paged.getContent().stream()
                .filter(pkg -> !unavailablePackageIds.contains(pkg.getId()))
                .toList();

        return toPage(filtered, page, size);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getAvailablePackages() {
        return getAvailablePackages(null, SortOrder.ASC, 0, 20);
    }

    /**
     * Returns true if the given transfer targets the specified user based on
     * acceptorType, matchUserId, and matchCompanyId filters.
     * Mirrors the subscription resolver's filter logic.
     */
    private boolean transferTargetsUser(TransferEntity transfer,
                                         Long userId, Role role, UUID companyId) {
        if (userId == null || role == null) return false;

        // matchUserId filter — only the specified user receives it
        if (transfer.getMatchUserId() != null
                && !transfer.getMatchUserId().equals(userId)) {
            return false;
        }

        // acceptorType filter
        if (transfer.getAcceptorType() != null) {
            switch (transfer.getAcceptorType()) {
                case DRIVER -> { if (role != Role.DRIVER) return false; }
                case WORKER -> { if (role != Role.WORKER) return false; }
                case BOTH -> {
                    if (role != Role.DRIVER && role != Role.WORKER) return false;
                }
            }
        }

        // matchCompanyId filter
        if (transfer.getMatchCompanyId() != null) {
            if (companyId == null || !transfer.getMatchCompanyId().equals(companyId)) {
                return false;
            }
        }

        return true;
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByCustodian(CustodianRole role, Long custodianId,
                                                      PackageStatus statusFilter, SortOrder order,
                                                      int page, int size) {
        var packages = custodianRepo.findByUserIdAndRole(custodianId, role).stream()
                .map(c -> packageRepo.findById(c.getPackageId()).orElse(null))
                .filter(Objects::nonNull)
                .toList();
        return toPage(filterAndSort(packages, statusFilter, order), page, size);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getPackagesByCustodian(CustodianRole role, Long custodianId) {
        return getPackagesByCustodian(role, custodianId, null, SortOrder.ASC, 0, 20);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getAllPackagesByCustodian(Long custodianId,
                                                         PackageStatus statusFilter, SortOrder order,
                                                         int page, int size) {
        var packages = custodianRepo.findByUserId(custodianId).stream()
                .map(c -> packageRepo.findById(c.getPackageId()).orElse(null))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return toPage(filterAndSort(packages, statusFilter, order), page, size);
    }

    @Transactional(readOnly = true)
    public DeliveryPackagePage getAllPackagesByCustodian(Long custodianId) {
        return getAllPackagesByCustodian(custodianId, null, SortOrder.ASC, 0, 20);
    }

    private PageRequest resolvePageRequest(SortOrder order, int page, int size) {
        var sort = order == SortOrder.DESC
                ? Sort.by(Sort.Direction.DESC, "createdAt")
                : Sort.by(Sort.Direction.ASC, "createdAt");
        return PageRequest.of(page, size, sort);
    }

    private List<PackageEntity> filterAndSort(List<PackageEntity> entities,
                                              PackageStatus statusFilter, SortOrder order) {
        if (statusFilter != null) {
            entities = entities.stream().filter(p -> p.getStatus() == statusFilter).toList();
        }
        if (order == SortOrder.DESC) {
            entities = entities.stream()
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .toList();
        } else {
            entities = entities.stream()
                    .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                    .toList();
        }
        return entities;
    }

    private DeliveryPackagePage toPage(Page<PackageEntity> page) {
        var items = page.getContent().stream().map(p -> toResponse(p, true)).toList();
        return new DeliveryPackagePage(items, (int) page.getTotalElements(), page.getTotalPages(), page.getNumber());
    }

    private DeliveryPackagePage toPage(List<PackageEntity> entities, int page, int size) {
        var totalCount = entities.size();
        var totalPages = (int) Math.ceil((double) totalCount / size);
        var items = entities.stream().map(p -> toResponse(p, true)).toList();
        return new DeliveryPackagePage(items, totalCount, totalPages, page);
    }

    @Transactional(readOnly = true)
    public List<PackageResponse.EventResponse> getPackageHistory(UUID packageId) {
        return eventRepo.findByPackageIdOrderByCreatedAtAsc(packageId).stream()
                .map(e -> new PackageResponse.EventResponse(
                        e.getId(), e.getEventType().name(), e.getActorId(),
                        e.getDescription(), e.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PackageResponse.CustodyResponse> getPackageCustodyHistory(UUID packageId) {
        return custodyRepo.findByPackageIdOrderByTimestampAsc(packageId).stream()
                .map(c -> new PackageResponse.CustodyResponse(
                        c.getId(), c.getFromEntity(), c.getToEntity(), c.getTimestamp(), c.getNotes()))
                .toList();
    }

    private String generateTrackingCode() {
        var chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var sb = new StringBuilder("CAV-");
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String generateDeliveryCode() {
        var chars = "0123456789";
        var sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String hashCode(String raw) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash code", e);
        }
    }

    private boolean verifyCode(String raw, String hash) {
        return hashCode(raw).equals(hash);
    }

    private void saveCustodian(UUID packageId, Long userId, CustodianRole role) {
        custodianRepo.save(PackageCustodianEntity.builder()
                .packageId(packageId)
                .userId(userId)
                .role(role)
                .assignedAt(Instant.now())
                .build());
    }

    private void savePerson(UUID packageId, CreatePackageInput.PersonInput input) {
        personRepo.save(PackagePersonEntity.builder()
                .packageId(packageId)
                .role(input.role())
                .userId(input.userId())
                .name(input.name())
                .phone(input.phone())
                .build());
    }

    private void saveLocation(UUID packageId, CreatePackageInput.LocationInput input) {
        locationRepo.save(PackageLocationEntity.builder()
                .packageId(packageId)
                .type(input.type())
                .latitude(input.latitude())
                .longitude(input.longitude())
                .placeName(input.placeName())
                .placeId(input.placeId())
                .officeId(input.officeId())
                .build());
    }

    private void saveEvent(UUID packageId, PackageEventType eventType, Long actorId, String description) {
        eventRepo.save(PackageEventEntity.builder()
                .packageId(packageId)
                .eventType(eventType)
                .actorId(actorId)
                .description(description)
                .createdAt(Instant.now())
                .build());
    }

    private void saveCustody(UUID packageId, String fromEntity, String toEntity, String notes) {
        custodyRepo.save(PackageCustodyEntity.builder()
                .packageId(packageId)
                .fromEntity(fromEntity)
                .toEntity(toEntity)
                .timestamp(Instant.now())
                .notes(notes)
                .build());
    }


    /**
     * Adds package IDs from REQUESTED transfers where the given user is either
     * the matchUserId (the intended recipient) or the requestorId (who accepted).
     * This ensures users see packages they have a pending offer on, even though
     * they are not yet custodians.
     */
    private void addPackageIdsFromRequestedTransfers(Long userId, LinkedHashSet<UUID> packageIds) {
        var byMatchUser = transferRepo.findByMatchUserIdAndStatus(userId, TransferStatus.REQUESTED);
        for (var transfer : byMatchUser) {
            transferPackageRepo.findPackageIdsByTransferId(transfer.getId())
                    .forEach(packageIds::add);
        }

        var byRequestor = transferRepo.findByRequestorIdAndStatus(userId, TransferStatus.REQUESTED);
        for (var transfer : byRequestor) {
            transferPackageRepo.findPackageIdsByTransferId(transfer.getId())
                    .forEach(packageIds::add);
        }
    }

    private PackageEventType mapStatusToEvent(PackageStatus status) {
        return switch (status) {
            case ACCEPTED -> PackageEventType.ACCEPTED;
            case PICKED_UP -> PackageEventType.PICKED_UP;
            case IN_TRANSIT -> PackageEventType.TRANSIT_STARTED;
            case PENDING_CONFIRMATION -> PackageEventType.DELIVERY_INITIATED;
            case DELIVERED -> PackageEventType.DELIVERED;
            case COMPLETED -> PackageEventType.COMPLETED;
            case CANCELLED -> PackageEventType.CANCELLED;
            case ORIGIN_OFFICE -> PackageEventType.ORIGIN_ARRIVED;
            case DESTINATION_OFFICE -> PackageEventType.DESTINATION_ARRIVED;
            case READY_FOR_COLLECTION -> PackageEventType.READY_FOR_COLLECTION;
            default -> PackageEventType.CREATED;
        };
    }

    private PackageResponse toResponse(PackageEntity pkg, boolean includeDetails) {
        var custodians = custodianRepo.findByPackageId(pkg.getId()).stream()
                .map(c -> {
                    var user = userRepository.findById(c.getUserId());
                    var name = user.map(u -> {
                        var full = (u.getFirstName() != null ? u.getFirstName() + " " : "") + (u.getLastName() != null ? u.getLastName() : "");
                        return full.isBlank() ? null : full.trim();
                    }).orElse(null);
                    var phone = user.map(u -> u.getPhone()).orElse(null);
                    return deliveryMapper.toCustodianResponse(c, name, phone);
                })
                .toList();

        var people = includeDetails ? personRepo.findByPackageId(pkg.getId()).stream()
                .map(deliveryMapper::toPersonResponse)
                .toList() : List.<PackageResponse.PersonResponse>of();

        var locations = includeDetails ? locationRepo.findByPackageId(pkg.getId()).stream()
                .map(deliveryMapper::toLocationResponse)
                .toList() : List.<PackageResponse.LocationResponse>of();

        var detail = includeDetails ? detailRepo.findByPackageId(pkg.getId()).map(d -> {
            var media = mediaRepo.findByPackageId(pkg.getId()).stream()
                    .map(m -> {
                        // Resolve URL from storage path — handles both local and Supabase
                        String url = null;
                        if (m.getStoragePath() != null && m.getBucket() != null) {
                            boolean isLocal = "local".equals(m.getStorageMode());
                            url = storageService.getFileUrl(m.getBucket(), m.getStoragePath(), isLocal);
                        }
                        // Derive MIME type from mediaType enum
                        String mime = m.getMediaType() == com.gocavgo.delivary.enums.delivery.MediaType.VIDEO
                                ? "video/mp4" : "image/jpeg";
                        return new PackageResponse.MediaResponse(
                                m.getId(), url != null ? url : "", mime);
                    })
                    .toList();
            return deliveryMapper.toDetailResponse(d, media);
        }).orElse(null) : null;

        var events = includeDetails ? eventRepo.findByPackageIdOrderByCreatedAtAsc(pkg.getId()).stream()
                .map(deliveryMapper::toEventResponse)
                .toList() : List.<PackageResponse.EventResponse>of();

        var custody = includeDetails ? custodyRepo.findByPackageIdOrderByTimestampAsc(pkg.getId()).stream()
                .map(deliveryMapper::toCustodyResponse)
                .toList() : List.<PackageResponse.CustodyResponse>of();

        // Look up any open (PENDING/REQUESTED) transfers for this package
        var transfers = transferService.getOpenTransfersByPackageId(pkg.getId());

        return deliveryMapper.toFullResponse(
                pkg, custodians, people, locations, detail, events, custody, transfers
        );
    }
}
