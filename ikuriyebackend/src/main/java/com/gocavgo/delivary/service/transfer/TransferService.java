package com.gocavgo.delivary.service.transfer;

import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.enums.transfer.TransferAcceptorType;
import com.gocavgo.delivary.enums.transfer.TransferRuleType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.service.notification.NoticeEventMapper;
import com.gocavgo.delivary.dto.transfer.input.AddPackagesToTransferInput;
import com.gocavgo.delivary.dto.transfer.input.CreateTransferInput;
import com.gocavgo.delivary.dto.transfer.input.RegenerateTransferCodeInput;
import com.gocavgo.delivary.dto.transfer.input.UpdateTransferInput;
import com.gocavgo.delivary.dto.delivery.output.TransferAcceptResult;
import com.gocavgo.delivary.dto.transfer.output.TransferResponse;
import com.gocavgo.delivary.entity.transfer.TransferEntity;
import com.gocavgo.delivary.service.delivery.PackageValidationService;
import com.gocavgo.delivary.mapper.transfer.TransferMapper;
import com.gocavgo.delivary.entity.transfer.TransferPackageEntity;
import com.gocavgo.delivary.service.notification.NoticeService;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.transfer.TransferJpaRepository;
import com.gocavgo.delivary.repository.transfer.TransferPackageJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TransferJpaRepository transferRepo;
    private final TransferPackageJpaRepository transferPackageRepo;
    private final TransferMapper transferMapper;
    private final NoticeService noticeService;
    private final PackageJpaRepository packageRepo;

    @Autowired @Lazy
    private com.gocavgo.delivary.service.delivery.PackageService packageService;

    private final PackageValidationService validationService;

    // ──────────────────────────────────────────────
    // Mutations
    // ──────────────────────────────────────────────

    @Transactional
    public TransferResponse createTransfer(Long creatorId, CreateTransferInput input) {
        log.info("createTransfer: creatorId={}, packageCount={}, ruleType={}",
                creatorId, input.packageIds().size(), input.ruleType());

        for (UUID packageId : input.packageIds()) {
            assertPackageNotInOpenTransfer(packageId, creatorId);
        }

        // Build the transfer entity
        var now = Instant.now();
        String codeHash = null;
        String rawCode = null;

        if (input.ruleType() == TransferRuleType.SECURE) {
            rawCode = generateTransferCode();
            codeHash = hashCode(rawCode);
        }

        var acceptorType = input.acceptorType() != null ? input.acceptorType() : TransferAcceptorType.BOTH;

        var entity = TransferEntity.builder()
                .creatorId(creatorId)
                .ruleType(input.ruleType())
                .acceptorType(acceptorType)
                .matchCompanyId(input.matchCompanyId())
                .matchUserId(input.matchUserId())
                .status(TransferStatus.PENDING)
                .transferCodeHash(codeHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
        transferRepo.save(entity);

        // Notify: transfer created (PENDING)
        noticeService.notifyTransferEvent(entity, NoticeEventMapper.fromTransferStatus(TransferStatus.PENDING), creatorId, null);

        // Link packages
        var pkgEntities = new ArrayList<TransferPackageEntity>();
        for (UUID packageId : input.packageIds()) {
            pkgEntities.add(TransferPackageEntity.builder()
                    .transferId(entity.getId())
                    .packageId(packageId)
                    .addedBy(creatorId)
                    .addedAt(now)
                    .build());
        }
        transferPackageRepo.saveAll(pkgEntities);

        log.info("createTransfer: done id={}, ruleType={}", entity.getId(), input.ruleType());
        return toResponse(entity, pkgEntities,
                input.ruleType() == TransferRuleType.SECURE ? rawCode : null);
    }

    @Transactional
    public TransferResponse addPackagesToTransfer(Long actorId, AddPackagesToTransferInput input) {
        log.info("addPackagesToTransfer: actorId={}, transferId={}, packageCount={}",
                actorId, input.transferId(), input.packageIds().size());

        var transfer = transferRepo.findById(input.transferId())
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + input.transferId()));

        assertOwner(transfer, actorId);

        var now = Instant.now();
        var added = new ArrayList<TransferPackageEntity>();

        for (UUID packageId : input.packageIds()) {
            // Check: package must not be in any open transfer
            assertPackageNotInOpenTransfer(packageId, actorId);

            added.add(TransferPackageEntity.builder()
                    .transferId(transfer.getId())
                    .packageId(packageId)
                    .addedBy(actorId)
                    .addedAt(now)
                    .build());
        }

        if (!added.isEmpty()) {
            transferPackageRepo.saveAll(added);
        }

        // Notify: package(s) added to transfer
        for (var tp : added) {
            var optionalPkg = packageRepo.findById(tp.getPackageId());
            optionalPkg.ifPresent(pkg -> noticeService.notifyTransferPackageAdded(transfer, pkg, actorId));
        }

        var allPackages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("addPackagesToTransfer: done, totalPackages={}", allPackages.size());
        return toResponse(transfer, allPackages, null);
    }

    @Transactional
    public TransferResponse regenerateTransferCode(Long actorId, RegenerateTransferCodeInput input) {
        // Import is from the same package — use the DTO already imported above
        log.info("regenerateTransferCode: actorId={}, transferId={}", actorId, input.transferId());

        var transfer = transferRepo.findById(input.transferId())
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + input.transferId()));

        assertOwner(transfer, actorId);

        if (transfer.getRuleType() != TransferRuleType.SECURE) {
            throw new BusinessValidationException("Cannot regenerate code for non-SECURE transfer");
        }

        var rawCode = generateTransferCode();
        var codeHash = hashCode(rawCode);
        transfer.setTransferCodeHash(codeHash);
        transfer.setUpdatedAt(Instant.now());
        transferRepo.save(transfer);

        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("regenerateTransferCode: done");
        return toResponse(transfer, packages, rawCode);
    }

    @Transactional
    public TransferResponse updateTransfer(Long actorId, UpdateTransferInput input) {
        log.info("updateTransfer: actorId={}, transferId={}", actorId, input.transferId());

        var transfer = transferRepo.findById(input.transferId())
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + input.transferId()));

        assertOwner(transfer, actorId);

        if (input.ruleType() != null) {
            transfer.setRuleType(input.ruleType());
        }
        if (input.acceptorType() != null) {
            transfer.setAcceptorType(input.acceptorType());
        }

        transfer.setUpdatedAt(Instant.now());
        transferRepo.save(transfer);

        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("updateTransfer: done");
        return toResponse(transfer, packages, null);
    }

    /**
     * Unified mutation to accept/request a transfer.
     * Behaviour depends on the transfer's ruleType:
     * - AUTO:  auto-accept all packages, complete the transfer.
     * - SECURE: verify transferCode, then auto-accept and complete.
     * - CONFIRM: set status to REQUESTED with actor as requestor.
     */
    @Transactional
    public TransferAcceptResult acceptTransfer(Long actorId, UUID transferId, String transferCode) {
        log.info("acceptTransfer: actorId={}, transferId={}, ruleType check", actorId, transferId);

        var transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + transferId));

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new BusinessValidationException("Transfer is not open for acceptance (status=" + transfer.getStatus() + ")");
        }

        if (transfer.getRuleType() == TransferRuleType.AUTO
                || transfer.getRuleType() == TransferRuleType.SECURE) {
            // Delegate to PackageService which handles package acceptance + transfer completion
            return packageService.acceptPackageByTransfer(actorId, transferId, transferCode);
        }

        if (transfer.getRuleType() == TransferRuleType.CONFIRM) {
            if (transfer.getCreatorId().equals(actorId)) {
                throw new BusinessValidationException("Transfer owner cannot request their own transfer");
            }

            // Validate actor is a WORKER or DRIVER
            var actorRole = packageService.validateAndGetAcceptorRole(actorId);

            // Validate that the requestor's role matches the transfer's acceptorType
            validationService.validateAcceptorType(transfer.getAcceptorType(), actorRole, transfer.getId());

            var previousStatus = transfer.getStatus();
            transfer.setRequestorId(actorId);
            transfer.setStatus(TransferStatus.REQUESTED);
            transfer.setUpdatedAt(Instant.now());
            transferRepo.save(transfer);

            // Notify: transfer requested
            noticeService.notifyTransferEvent(transfer, NoticeEventMapper.fromTransferStatus(TransferStatus.REQUESTED), actorId, previousStatus);

            var packages = transferPackageRepo.findByTransferId(transfer.getId());
            var transferResp = toResponse(transfer, packages, null);
            log.info("acceptTransfer: CONFIRM mode, status=REQUESTED");
            return new TransferAcceptResult(transferResp, List.of());
        }

        throw new BusinessValidationException("Unknown transfer rule type: " + transfer.getRuleType());
    }

    @Transactional
    public TransferResponse confirmTransfer(Long actorId, UUID transferId) {
        log.info("confirmTransfer: actorId={}, transferId={}", actorId, transferId);

        var transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + transferId));

        assertOwner(transfer, actorId);

        if (transfer.getRuleType() != TransferRuleType.CONFIRM) {
            throw new BusinessValidationException("Only CONFIRM transfers can be confirmed");
        }
        if (transfer.getStatus() != TransferStatus.REQUESTED) {
            throw new BusinessValidationException("Transfer is not in REQUESTED status (status=" + transfer.getStatus() + ")");
        }

        var requestorId = transfer.getRequestorId();
        if (requestorId == null) {
            throw new BusinessValidationException("Transfer has no requestor to accept packages");
        }

        // ★ Auto-accept all packages in the transfer — requestor becomes custodian
        packageService.acceptPackagesForTransferConfirmation(requestorId, transferId);

        var previousStatus = transfer.getStatus();
        transfer.setStatus(TransferStatus.DONE);
        transfer.setUpdatedAt(Instant.now());
        transferRepo.save(transfer);

        // Notify: transfer completed
        noticeService.notifyTransferEvent(transfer, NoticeEventMapper.fromTransferStatus(TransferStatus.DONE), actorId, previousStatus);

        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("confirmTransfer: done, status=DONE, packages auto-accepted for requestor={}", requestorId);
        return toResponse(transfer, packages, null);
    }

    @Transactional
    public TransferResponse rejectTransfer(Long actorId, UUID transferId) {
        log.info("rejectTransfer: actorId={}, transferId={}", actorId, transferId);

        var transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + transferId));

        assertOwner(transfer, actorId);

        if (transfer.getRuleType() != TransferRuleType.CONFIRM) {
            throw new BusinessValidationException("Only CONFIRM transfers can be rejected");
        }
        if (transfer.getStatus() != TransferStatus.REQUESTED) {
            throw new BusinessValidationException("Transfer is not in REQUESTED status (status=" + transfer.getStatus() + ")");
        }

        // Reset back to PENDING so someone else can request
        var previousStatus = transfer.getStatus();
        transfer.setRequestorId(null);
        transfer.setStatus(TransferStatus.PENDING);
        transfer.setUpdatedAt(Instant.now());
        transferRepo.save(transfer);

        // Notify: transfer reverted to PENDING
        noticeService.notifyTransferEvent(transfer, NoticeEventMapper.fromTransferStatus(TransferStatus.PENDING), actorId, previousStatus);

        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("rejectTransfer: done, status=PENDING");
        return toResponse(transfer, packages, null);
    }

    @Transactional
    public TransferResponse cancelTransfer(Long actorId, UUID transferId) {
        log.info("cancelTransfer: actorId={}, transferId={}", actorId, transferId);

        var transfer = transferRepo.findById(transferId)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + transferId));

        assertOwner(transfer, actorId);

        if (transfer.getStatus() == TransferStatus.DONE) {
            throw new BusinessValidationException("Cannot cancel a completed transfer");
        }
        if (transfer.getStatus() == TransferStatus.CANCELED) {
            throw new BusinessValidationException("Transfer is already cancelled");
        }

        var previousStatus = transfer.getStatus();
        transfer.setStatus(TransferStatus.CANCELED);
        transfer.setUpdatedAt(Instant.now());
        transferRepo.save(transfer);

        // Notify: transfer cancelled
        noticeService.notifyTransferEvent(transfer, NoticeEventMapper.fromTransferStatus(TransferStatus.CANCELED), actorId, previousStatus);

        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        log.info("cancelTransfer: done");
        return toResponse(transfer, packages, null);
    }

    // ──────────────────────────────────────────────
    // Queries
    // ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TransferResponse getTransferById(UUID id) {
        var transfer = transferRepo.findById(id)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + id));
        var packages = transferPackageRepo.findByTransferId(transfer.getId());
        return toResponse(transfer, packages, null);
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> getTransfersByCreator(Long creatorId) {
        return transferRepo.findByCreatorId(creatorId).stream()
                .map(t -> {
                    var pkgs = transferPackageRepo.findByTransferId(t.getId());
                    return toResponse(t, pkgs, null);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> getTransfersByStatus(TransferStatus status) {
        return transferRepo.findByStatus(status).stream()
                .map(t -> {
                    var pkgs = transferPackageRepo.findByTransferId(t.getId());
                    return toResponse(t, pkgs, null);
                })
                .toList();
    }

    /**
     * Returns PENDING transfers targeted at the given user via matchUserId.
     * Used by drivers to see packages transferred to them by workers.
     */
    @Transactional(readOnly = true)
    public List<TransferResponse> getTransfersForMatchedUser(Long userId) {
        return transferRepo.findByMatchUserIdAndStatus(userId, TransferStatus.PENDING).stream()
                .map(t -> {
                    var pkgs = transferPackageRepo.findByTransferId(t.getId());
                    return toResponse(t, pkgs, null);
                })
                .toList();
    }

    // ──────────────────────────────────────────────
    // Public helpers (used by PackageService)
    // ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public TransferEntity getTransferEntityById(UUID id) {
        return transferRepo.findById(id)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + id));
    }

    /**
     * Loads a transfer with a pessimistic write lock — prevents concurrent
     * acceptance or mutation of the same transfer by multiple transactions.
     */
    @Transactional
    public TransferEntity getTransferEntityWithLock(UUID id) {
        return transferRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessValidationException("Transfer not found: " + id));
    }

    @Transactional
    public void saveTransferEntity(TransferEntity entity) {
        transferRepo.save(entity);
    }

    /**
     * Returns open transfers (PENDING, REQUESTED) that contain the given package.
     * A package can have multiple historical transfer links (for traceability),
     * but only one should be non-finalized (PENDING or REQUESTED) at a time.
     * Used by PackageService to append open transfer info to package responses,
     * so the frontend can determine whether to prompt for a transfer code.
     */
    @Transactional(readOnly = true)
    public List<TransferResponse> getOpenTransfersByPackageId(UUID packageId) {
        var links = transferPackageRepo.findByPackageId(packageId);
        if (links.isEmpty()) return List.of();

        var results = new ArrayList<TransferResponse>();
        for (var link : links) {
            var transferEntity = transferRepo.findById(link.getTransferId()).orElse(null);
            if (transferEntity == null) continue;

            // Only include transfers that are still open
            if (transferEntity.getStatus() == TransferStatus.PENDING
                    || transferEntity.getStatus() == TransferStatus.REQUESTED) {
                var packages = transferPackageRepo.findByTransferId(transferEntity.getId());
                results.add(toResponse(transferEntity, packages, null));
            }
        }
        return results;
    }

    public TransferResponse toResponse(TransferEntity entity,
                                        List<TransferPackageEntity> packages,
                                        String transferCode) {
        return transferMapper.toResponseWithCode(entity, packages, transferCode);
    }


    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Asserts that a package can be added to a new transfer.
     * Rules:
     * - If the package has an open (PENDING) transfer owned by a DIFFERENT user → throws
     * - If the package has an open (PENDING) transfer owned by the SAME user:
     *   The package will be in both old and new transfers (historical traceability).
     *   If the old transfer ends up with no other packages, it gets auto-cancelled.
     * - DONE / CANCELED transfers are kept as-is (historical traceability).
     */
    /**
     * A package may be linked to MANY transfers over its lifetime (completed /
     * cancelled transfers are kept for history), but only ONE of them may be
     * OPEN (PENDING/REQUESTED) at a time:
     * - No open link → allowed (previous completed/cancelled transfers are fine).
     * - Existing open link owned by the same caller → the package is released from
     *   the old transfer (link removed) and, if the old transfer becomes empty,
     *   it is auto-cancelled. The package is then free to be linked to the new one.
     * - Existing open link owned by another user → rejected.
     */
    private void assertPackageNotInOpenTransfer(UUID packageId, Long actorId) {
        var links = transferPackageRepo.findByPackageId(packageId);
        if (links.isEmpty()) return;

        for (var link : links) {
            var oldTransfer = transferRepo.findById(link.getTransferId()).orElse(null);
            if (oldTransfer == null) {
                // Orphaned link — clean it up
                transferPackageRepo.delete(link);
                continue;
            }

            // Completed / cancelled transfers are history — they never block re-linking.
            if (oldTransfer.getStatus() != TransferStatus.PENDING
                    && oldTransfer.getStatus() != TransferStatus.REQUESTED) {
                continue;
            }

            if (!oldTransfer.getCreatorId().equals(actorId)) {
                throw new BusinessValidationException(
                        "Package " + packageId + " is already in an open transfer " + oldTransfer.getId()
                                + " owned by another user. Only the creator of the old transfer can move it.");
            }

            // Same owner: release the package from the old open transfer so the package
            // has at most one open link, and cancel the old transfer if it becomes empty.
            log.info("assertPackageNotInOpenTransfer: releasing package {} from transfer {}",
                    packageId, oldTransfer.getId());
            transferPackageRepo.delete(link);
            if (transferPackageRepo.countByTransferId(oldTransfer.getId()) == 0) {
                log.info("assertPackageNotInOpenTransfer: cancelling transfer {} (no other packages)",
                        oldTransfer.getId());
                oldTransfer.setStatus(TransferStatus.CANCELED);
                oldTransfer.setUpdatedAt(Instant.now());
                transferRepo.save(oldTransfer);
            }
        }
    }

    private void assertOwner(TransferEntity transfer, Long actorId) {
        if (!transfer.getCreatorId().equals(actorId)) {
            throw new BusinessValidationException("Only the transfer owner can perform this action");
        }
    }

    // ──────────────────────────────────────────────
    // Code generation / hashing
    // ──────────────────────────────────────────────

    private String generateTransferCode() {
        // 8-character alphanumeric code
        var chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
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
            throw new RuntimeException("Failed to hash transfer code", e);
        }
    }

}

