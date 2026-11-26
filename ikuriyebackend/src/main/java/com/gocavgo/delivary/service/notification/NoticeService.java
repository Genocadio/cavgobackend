package com.gocavgo.delivary.service.notification;

import com.gocavgo.delivary.controller.graphql.NoticeResolver;
import com.gocavgo.delivary.entity.delivery.OfficeClaimEntity;
import com.gocavgo.delivary.entity.delivery.PackageAssignmentEntity;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.notification.NoticeEntity;
import com.gocavgo.delivary.entity.notification.NoticeViewerEntity;
import com.gocavgo.delivary.entity.transfer.TransferEntity;
import com.gocavgo.delivary.entity.transfer.TransferPackageEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.notification.NoticeEventType;
import com.gocavgo.delivary.enums.notification.NoticeResourceType;
import com.gocavgo.delivary.enums.transfer.TransferStatus;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackagePersonJpaRepository;
import com.gocavgo.delivary.repository.notification.NoticeRepository;
import com.gocavgo.delivary.repository.notification.NoticeViewerRepository;
import com.gocavgo.delivary.repository.transfer.TransferPackageJpaRepository;
import com.gocavgo.delivary.service.subscription.NoticePublisher;
import com.gocavgo.delivary.util.AfterCommitActions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NoticeRepository noticeRepo;
    private final NoticeViewerRepository viewerRepo;
    private final PackagePersonJpaRepository personRepo;
    private final PackageCustodianJpaRepository custodianRepo;
    private final TransferPackageJpaRepository transferPackageRepo;
    private final com.gocavgo.delivary.repository.delivery.PackageJpaRepository packageRepo;
    private final NoticePublisher noticePublisher;

    // ── Title / message templates ───────────────────────────────────────

    private static final Map<NoticeEventType, String[]> TEMPLATES = Map.ofEntries(
            Map.entry(NoticeEventType.PACKAGE_CREATED,             new String[]{"Package created",           "Package %s has been created."}),
            Map.entry(NoticeEventType.PACKAGE_ACCEPTED,            new String[]{"Package accepted",          "Package %s has been accepted."}),
            Map.entry(NoticeEventType.PACKAGE_PICKED_UP,           new String[]{"Package picked up",         "Package %s has been picked up."}),
            Map.entry(NoticeEventType.PACKAGE_IN_TRANSIT,          new String[]{"Package in transit",        "Package %s is now in transit."}),
            Map.entry(NoticeEventType.PACKAGE_DELIVERY_INITIATED,  new String[]{"Delivery confirmation pending", "Package %s is awaiting delivery confirmation."}),
            Map.entry(NoticeEventType.PACKAGE_DELIVERED,           new String[]{"Package delivered",         "Package %s has been delivered."}),
            Map.entry(NoticeEventType.PACKAGE_COMPLETED,           new String[]{"Package completed",         "Package %s has been completed."}),
            Map.entry(NoticeEventType.PACKAGE_CANCELLED,           new String[]{"Package cancelled",         "Package %s has been cancelled."}),
            Map.entry(NoticeEventType.PACKAGE_ORIGIN_OFFICE,       new String[]{"At origin office",          "Package %s has arrived at the origin office."}),
            Map.entry(NoticeEventType.PACKAGE_ASSIGNED_DRIVER,     new String[]{"Driver assigned",           "A driver has been assigned to package %s."}),
            Map.entry(NoticeEventType.PACKAGE_ASSIGNMENT_OBJECTED, new String[]{"Driver disputed assignment", "The driver reported package %s as not received."}),
            Map.entry(NoticeEventType.PACKAGE_OFFICE_CLAIM_CREATED, new String[]{"Claimed by destination office", "Package %s was claimed by the destination office."}),
            Map.entry(NoticeEventType.PACKAGE_OFFICE_CLAIM_DISPUTED, new String[]{"Office claim disputed", "The driver reported package %s as not delivered to the office."}),
            Map.entry(NoticeEventType.PACKAGE_DESTINATION_OFFICE,  new String[]{"At destination office",     "Package %s has arrived at the destination office."}),
            Map.entry(NoticeEventType.PACKAGE_READY_FOR_COLLECTION,new String[]{"Ready for collection",      "Package %s is ready for collection."}),
            Map.entry(NoticeEventType.PACKAGE_CUSTODIAN_ASSIGNED,  new String[]{"Custodian assigned",        "A new custodian has been assigned to package %s."}),
            Map.entry(NoticeEventType.PACKAGE_CUSTODIAN_REMOVED,   new String[]{"Custodian removed",         "You are no longer a custodian of package %s."}),
            Map.entry(NoticeEventType.PACKAGE_CUSTODY_TRANSFERRED, new String[]{"Custody transferred",       "Custody of package %s has been transferred."}),
            Map.entry(NoticeEventType.TRANSFER_PENDING,            new String[]{"Package transferred to you",  "A package has been transferred to you for acceptance."}),
            Map.entry(NoticeEventType.TRANSFER_REQUESTED,          new String[]{"Transfer requested",        "A transfer has been requested."}),
            Map.entry(NoticeEventType.TRANSFER_DONE,               new String[]{"Transfer completed",        "Transfer has been completed."}),
            Map.entry(NoticeEventType.TRANSFER_CANCELED,           new String[]{"Transfer cancelled",        "Transfer has been cancelled."}),
            Map.entry(NoticeEventType.TRANSFER_PACKAGE_ADDED,      new String[]{"Package added to transfer", "Package %s has been added to a transfer."})
    );

    // ── Public entry points ─────────────────────────────────────────────

    /**
     * Fires a notice for a package status change. Must be called inside the
     * same transaction that mutates the package.
     */
    @Transactional
    public void notifyPackageEvent(PackageEntity pkg, NoticeEventType eventType,
                                   Long actorId, PackageStatus previousStatus) {
        var recipients = resolvePackageRecipients(pkg.getId(), eventType, actorId);
        if (recipients.isEmpty()) {
            log.debug("notifyPackageEvent: no recipients for package={}, event={}", pkg.getId(), eventType);
            return;
        }

        var payload = buildPackagePayload(pkg, eventType, actorId, previousStatus);
        var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(), eventType, actorId, payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), recipients);
        log.debug("notifyPackageEvent: notice={} for package={}, {} recipient(s)", notice.getId(), pkg.getId(), recipients.size());
    }

    /**
     * Fires a notice for a transfer status change. Must be called inside the
     * same transaction that mutates the transfer.
     */
    @Transactional
    public void notifyTransferEvent(TransferEntity transfer, NoticeEventType eventType,
                                    Long actorId, TransferStatus previousStatus) {
        var recipients = resolveTransferRecipients(transfer, eventType, actorId);
        if (recipients.isEmpty()) {
            log.debug("notifyTransferEvent: no recipients for transfer={}, event={}", transfer.getId(), eventType);
            return;
        }

        // Include package IDs and tracking codes in all transfer notices so
        // clients can always resolve and open the affected package(s).
        var packages = resolveTransferPackages(transfer.getId());
        var payload = buildTransferPayload(transfer, eventType, actorId, previousStatus, packages);
        String primaryTrackingCode = packages.stream()
                .map(PackageEntity::getTrackingCode)
                .filter(code -> code != null && !code.isBlank())
                .findFirst()
                .orElse(null);
        var notice = saveNotice(NoticeResourceType.TRANSFER, transfer.getId(), eventType, actorId, payload, primaryTrackingCode);
        saveViewers(notice.getId(), recipients);
        log.debug("notifyTransferEvent: notice={} for transfer={}, {} recipient(s)", notice.getId(), transfer.getId(), recipients.size());
    }

    /**
     * Fires a notice targeted at a specific user who has been removed as a custodian.
     * The removed user receives the notice directly — the standard recipient-resolution
     * path wouldn't include them since they are no longer an active custodian.
     */
    @Transactional
    public void notifyPackageCustodianRemoved(PackageEntity pkg, Long removedUserId, Long actorBy) {
        var payload = buildPackagePayload(pkg, NoticeEventType.PACKAGE_CUSTODIAN_REMOVED, actorBy, null);
        var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(),
                NoticeEventType.PACKAGE_CUSTODIAN_REMOVED, actorBy,
                payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), Set.of(removedUserId));
        log.debug("notifyPackageCustodianRemoved: notice={} for user={}, package={}",
                notice.getId(), removedUserId, pkg.getId());
    }

    /**
     * Fires a notice to the assigning worker (and other non-driver custodians) when the
     * driver marks a direct assignment as not received. Custody intentionally stays with
     * the driver; this is a dispute flag, not a handoff.
     */
    @Transactional
    public void notifyAssignmentObjected(PackageEntity pkg,
                                         PackageAssignmentEntity assignment,
                                         Long driverId,
                                         Long assignedBy,
                                         String reason) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (assignedBy != null && !assignedBy.equals(driverId)) {
            recipients.add(assignedBy);
        }
        custodianRepo.findByPackageId(pkg.getId()).stream()
                .map(PackageCustodianEntity::getUserId)
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(driverId))
                .forEach(recipients::add);

        if (recipients.isEmpty()) {
            log.debug("notifyAssignmentObjected: no recipients for package={}", pkg.getId());
            return;
        }

        var payload = buildAssignmentObjectionPayload(pkg, assignment, driverId, reason);
        var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(),
                NoticeEventType.PACKAGE_ASSIGNMENT_OBJECTED, driverId,
                payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), recipients);
        log.debug("notifyAssignmentObjected: notice={} for package={}, {} recipient(s)",
                notice.getId(), pkg.getId(), recipients.size());
    }

    /**
     * A destination office claimed the package directly. Notify the driver who
     * lost custody; the claiming worker is the actor and does not get a notice.
     */
    @Transactional
    public void notifyOfficeClaimCreated(PackageEntity pkg,
                                         OfficeClaimEntity claim,
                                         Long workerId,
                                         Long driverId) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (claim.getDriverId() != null && !claim.getDriverId().equals(workerId)) {
            recipients.add(claim.getDriverId());
        }
        if (driverId != null && !driverId.equals(workerId)) {
            recipients.add(driverId);
        }
        custodianRepo.findByPackageId(pkg.getId()).stream()
                .filter(c -> c.getRole() == CustodianRole.DRIVER)
                .map(PackageCustodianEntity::getUserId)
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(workerId))
                .forEach(recipients::add);

        if (recipients.isEmpty()) {
            log.debug("notifyOfficeClaimCreated: no recipients for package={}", pkg.getId());
            return;
        }

        var payload = buildOfficeClaimPayload(pkg, claim, workerId, "notes", claim.getNotes());
        var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(),
                NoticeEventType.PACKAGE_OFFICE_CLAIM_CREATED, workerId,
                payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), recipients);
        log.debug("notifyOfficeClaimCreated: notice={} for package={}, {} recipient(s)",
                notice.getId(), pkg.getId(), recipients.size());
    }

    /**
     * The driver contests a direct office claim. Custody stays with the office;
     * the claiming worker and other non-driver custodians are notified.
     */
    @Transactional
    public void notifyOfficeClaimDisputed(PackageEntity pkg,
                                          OfficeClaimEntity claim,
                                          Long driverId,
                                          Long workerId,
                                          String reason) {
        Set<Long> recipients = new LinkedHashSet<>();
        if (claim.getClaimedBy() != null && !claim.getClaimedBy().equals(driverId)) {
            recipients.add(claim.getClaimedBy());
        }
        if (workerId != null && !workerId.equals(driverId)) {
            recipients.add(workerId);
        }
        custodianRepo.findByPackageId(pkg.getId()).stream()
                .map(PackageCustodianEntity::getUserId)
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(driverId))
                .forEach(recipients::add);

        if (recipients.isEmpty()) {
            log.debug("notifyOfficeClaimDisputed: no recipients for package={}", pkg.getId());
            return;
        }

        var payload = buildOfficeClaimPayload(pkg, claim, driverId, "disputeReason", reason);
        var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(),
                NoticeEventType.PACKAGE_OFFICE_CLAIM_DISPUTED, driverId,
                payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), recipients);
        log.debug("notifyOfficeClaimDisputed: notice={} for package={}, {} recipient(s)",
                notice.getId(), pkg.getId(), recipients.size());
    }

    /**
     * Fires a notice when a package is added to a transfer.
     */
    @Transactional
    public void notifyTransferPackageAdded(TransferEntity transfer, PackageEntity pkg, Long actorId) {
        var recipients = resolveTransferRecipients(transfer, NoticeEventType.TRANSFER_PACKAGE_ADDED, actorId);
        // Also include the package's current custodian and people
        recipients.addAll(resolvePackageRecipients(pkg.getId(), NoticeEventType.TRANSFER_PACKAGE_ADDED, actorId));
        if (recipients.isEmpty()) {
            log.debug("notifyTransferPackageAdded: no recipients for transfer={}, package={}", transfer.getId(), pkg.getId());
            return;
        }

        var payload = buildPackageAddedPayload(transfer, pkg, actorId);
        var notice = saveNotice(NoticeResourceType.TRANSFER, transfer.getId(),
                NoticeEventType.TRANSFER_PACKAGE_ADDED, actorId, payload, pkg.getTrackingCode());
        saveViewers(notice.getId(), recipients);
        log.debug("notifyTransferPackageAdded: notice={} for transfer={}, {} recipient(s)",
                notice.getId(), transfer.getId(), recipients.size());
    }

    /**
     * Fires a notice when a delivery is initiated for a package. The notice
     * carries the status change (previousStatus → PENDING_CONFIRMATION) plus the
     * one-time delivery code. It is delivered ONLY to the people on the package
     * (sender/receiver) — never to custodians, so the delivery can only be
     * confirmed by the parties the code was meant for. Sender and receiver receive
     * role-appropriate messages. Must be called inside the same transaction that
     * transitions the package to PENDING_CONFIRMATION.
     */
    @Transactional
    public void notifyDeliveryCodeIssued(PackageEntity pkg, String deliveryCode, Long actorId, PackageStatus previousStatus) {
        var persons = personRepo.findByPackageId(pkg.getId()).stream()
                .filter(p -> p.getUserId() != null)
                .toList();
        if (persons.isEmpty()) {
            log.debug("notifyDeliveryCodeIssued: no recipients for package={}", pkg.getId());
            return;
        }

        var payload = buildDeliveryCodePayload(pkg, deliveryCode, actorId, previousStatus);

        var senderRecipients = persons.stream()
                .filter(p -> p.getRole() == com.gocavgo.delivary.enums.delivery.PersonRole.SENDER)
                .map(com.gocavgo.delivary.entity.delivery.PackagePersonEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var receiverRecipients = persons.stream()
                .filter(p -> p.getRole() == com.gocavgo.delivary.enums.delivery.PersonRole.RECEIVER)
                .map(com.gocavgo.delivary.entity.delivery.PackagePersonEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var otherRecipients = persons.stream()
                .filter(p -> p.getRole() != com.gocavgo.delivary.enums.delivery.PersonRole.SENDER
                        && p.getRole() != com.gocavgo.delivary.enums.delivery.PersonRole.RECEIVER)
                .map(com.gocavgo.delivary.entity.delivery.PackagePersonEntity::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        excludeActor(senderRecipients, NoticeEventType.PACKAGE_DELIVERY_INITIATED, actorId);
        excludeActor(receiverRecipients, NoticeEventType.PACKAGE_DELIVERY_INITIATED, actorId);
        excludeActor(otherRecipients, NoticeEventType.PACKAGE_DELIVERY_INITIATED, actorId);

        // A user can appear in multiple roles in edge cases; avoid duplicate/conflicting notices.
        receiverRecipients.removeAll(senderRecipients);
        otherRecipients.removeAll(senderRecipients);
        otherRecipients.removeAll(receiverRecipients);

        if (!receiverRecipients.isEmpty()) {
            var title = "Delivery confirmation needed";
            var message = pkg.getTrackingCode() != null
                    ? String.format("Package %s is ready for delivery confirmation. Use the delivery code to confirm receipt.", pkg.getTrackingCode())
                    : "Your package is ready for delivery confirmation. Use the delivery code to confirm receipt.";
            var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(), NoticeEventType.PACKAGE_DELIVERY_INITIATED,
                    actorId, title, message, payload);
            saveViewers(notice.getId(), receiverRecipients);
            log.debug("notifyDeliveryCodeIssued: receiver notice={} for package={}, {} recipient(s)",
                    notice.getId(), pkg.getId(), receiverRecipients.size());
        }

        if (!senderRecipients.isEmpty()) {
            var title = "Delivery confirmation pending";
            var message = pkg.getTrackingCode() != null
                    ? String.format("Package %s is awaiting delivery confirmation. Share the delivery code with the receiver if needed so delivery can be confirmed.", pkg.getTrackingCode())
                    : "Your package is awaiting delivery confirmation. Share the delivery code with the receiver if needed so delivery can be confirmed.";
            var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(), NoticeEventType.PACKAGE_DELIVERY_INITIATED,
                    actorId, title, message, payload);
            saveViewers(notice.getId(), senderRecipients);
            log.debug("notifyDeliveryCodeIssued: sender notice={} for package={}, {} recipient(s)",
                    notice.getId(), pkg.getId(), senderRecipients.size());
        }

        if (!otherRecipients.isEmpty()) {
            var title = "Delivery confirmation pending";
            var message = pkg.getTrackingCode() != null
                    ? String.format("Package %s is awaiting delivery confirmation. Use the delivery code to confirm delivery if necessary.", pkg.getTrackingCode())
                    : "This package is awaiting delivery confirmation. Use the delivery code to confirm delivery if necessary.";
            var notice = saveNotice(NoticeResourceType.PACKAGE, pkg.getId(), NoticeEventType.PACKAGE_DELIVERY_INITIATED,
                    actorId, title, message, payload);
            saveViewers(notice.getId(), otherRecipients);
            log.debug("notifyDeliveryCodeIssued: notice={} for package={}, {} recipient(s)",
                    notice.getId(), pkg.getId(), otherRecipients.size());
        }

        if (receiverRecipients.isEmpty() && senderRecipients.isEmpty() && otherRecipients.isEmpty()) {
            log.debug("notifyDeliveryCodeIssued: no eligible recipients for package={} after actor/role filtering", pkg.getId());
        }
    }

    // ── Recipient resolution ────────────────────────────────────────────

    private Set<Long> resolvePackageRecipients(UUID packageId, NoticeEventType eventType, Long actorId) {
        var userIds = new LinkedHashSet<Long>();

        if (!CUSTODIAN_ONLY_EVENTS.contains(eventType)) {
            personRepo.findByPackageId(packageId).stream()
                    .map(p -> p.getUserId())
                    .filter(u -> u != null)
                    .forEach(userIds::add);
        }

        if (!PEOPLE_ONLY_EVENTS.contains(eventType)) {
            // Only the CURRENT custodian is notified — past custodians were already
            // informed via PACKAGE_CUSTODIAN_REMOVED when they handed off.
            custodianRepo.findTopByPackageIdOrderByAssignedAtDesc(packageId)
                    .map(PackageCustodianEntity::getUserId)
                    .ifPresent(userIds::add);
        }

        return excludeActor(userIds, eventType, actorId);
    }



    private Set<Long> resolveTransferRecipients(TransferEntity transfer, NoticeEventType eventType, Long actorId) {
        var userIds = new LinkedHashSet<Long>();

        // Context-aware targeting — each transfer event reaches only the users
        // whose status actually changed, never the whole package custodian set.
        switch (eventType) {
            case TRANSFER_REQUESTED -> {
                // Only the owner can approve a CONFIRM request.
                if (transfer.getCreatorId() != null) userIds.add(transfer.getCreatorId());
            }
            case TRANSFER_DONE -> {
                // The requestor becomes custodian — they were the one waiting for this.
                if (transfer.getRequestorId() != null) userIds.add(transfer.getRequestorId());
                if (transfer.getCreatorId() != null) userIds.add(transfer.getCreatorId());
            }
            case TRANSFER_PENDING -> {
                // Only the specific target (and the creator) — the open pool itself
                // is served live via the newPackageTransfer WebSocket subscription.
                if (transfer.getCreatorId() != null) userIds.add(transfer.getCreatorId());
                if (transfer.getMatchUserId() != null) userIds.add(transfer.getMatchUserId());
            }
            default -> {
                if (transfer.getCreatorId() != null) userIds.add(transfer.getCreatorId());
                if (transfer.getRequestorId() != null) userIds.add(transfer.getRequestorId());
                if (transfer.getMatchUserId() != null) userIds.add(transfer.getMatchUserId());
            }
        }

        return excludeActor(userIds, eventType, actorId);
    }

    /**
     * Events whose notice goes ONLY to the package people (sender/receiver) —
     * never to custodians.
     */
    private static final Set<NoticeEventType> PEOPLE_ONLY_EVENTS = Set.of(
            NoticeEventType.PACKAGE_DELIVERY_INITIATED
    );

    /**
     * Internal custody operations — custodians only, never customers.
     */
    private static final Set<NoticeEventType> CUSTODIAN_ONLY_EVENTS = Set.of(
            NoticeEventType.PACKAGE_CUSTODIAN_ASSIGNED,
            NoticeEventType.PACKAGE_CUSTODY_TRANSFERRED
    );

    private static final Set<NoticeEventType> EXCLUDE_ACTOR_TYPES = Set.of(
            NoticeEventType.PACKAGE_CREATED,
            NoticeEventType.PACKAGE_ACCEPTED,
            NoticeEventType.PACKAGE_PICKED_UP,
            NoticeEventType.PACKAGE_IN_TRANSIT,
            NoticeEventType.PACKAGE_DELIVERY_INITIATED,
            NoticeEventType.PACKAGE_DELIVERED,
            NoticeEventType.PACKAGE_COMPLETED,
            NoticeEventType.PACKAGE_CANCELLED,
            NoticeEventType.PACKAGE_ORIGIN_OFFICE,
            NoticeEventType.PACKAGE_ASSIGNED_DRIVER,
            NoticeEventType.PACKAGE_DESTINATION_OFFICE,
            NoticeEventType.PACKAGE_READY_FOR_COLLECTION,
            NoticeEventType.PACKAGE_CUSTODIAN_ASSIGNED,
            NoticeEventType.PACKAGE_CUSTODY_TRANSFERRED,
            NoticeEventType.TRANSFER_PENDING,
            NoticeEventType.TRANSFER_REQUESTED,
            NoticeEventType.TRANSFER_DONE,
            NoticeEventType.TRANSFER_CANCELED,
            NoticeEventType.TRANSFER_PACKAGE_ADDED
    );

    /**
     * Removes the actor from the recipient set for status-change events,
     * so the person who made the change doesn't get a notice about their own action.
     */
    private Set<Long> excludeActor(Set<Long> userIds, NoticeEventType eventType, Long actorId) {
        if (actorId != null && EXCLUDE_ACTOR_TYPES.contains(eventType)) {
            userIds.remove(actorId);
        }
        return userIds;
    }

    // ── Persistence helpers ─────────────────────────────────────────────

    private NoticeEntity saveNotice(NoticeResourceType resourceType, UUID resourceId,
                                     NoticeEventType eventType, Long actorId,
                                     String payload, String trackingCodeOrNull) {
        var templates = TEMPLATES.get(eventType);
        var title = templates != null ? templates[0] : eventType.name();
        var messageTemplate = templates != null ? templates[1] : "An update is available.";
        var message = trackingCodeOrNull != null
                ? String.format(messageTemplate, trackingCodeOrNull)
                : messageTemplate;

        return saveNotice(resourceType, resourceId, eventType, actorId, title, message, payload);
    }

    private NoticeEntity saveNotice(NoticeResourceType resourceType, UUID resourceId,
                                     NoticeEventType eventType, Long actorId,
                                     String title, String message, String payload) {
        var notice = NoticeEntity.builder()
                .resourceType(resourceType)
                .resourceId(resourceId)
                .eventType(eventType)
                .actorId(actorId)
                .title(title)
                .message(message)
                .payload(payload)
                .createdAt(Instant.now())
                .build();
        return noticeRepo.save(notice);
    }

    private void saveViewers(UUID noticeId, Set<Long> userIds) {
        var now = Instant.now();
        var viewers = userIds.stream()
                .map(uid -> NoticeViewerEntity.builder()
                        .noticeId(noticeId)
                        .userId(uid)
                        .deliveredAt(now)
                        .createdAt(now)
                        .build())
                .toList();
        var saved = viewerRepo.saveAll(viewers);

        // Publish each notice to the GraphQL subscription so connected clients
        // receive real-time updates (replaces Supabase Realtime).
        var notice = noticeRepo.findById(noticeId).orElse(null);
        if (notice != null) {
            for (var viewer : saved) {
                var response = new NoticeResolver.NoticeResponse(notice, viewer);
                AfterCommitActions.run(() -> noticePublisher.publish(
                        new NoticePublisher.NoticeEvent(viewer.getUserId(), response)));
            }
        }
    }

    // ── Payload builders ────────────────────────────────────────────────

    /**
     * Builds a JSON-safe map that tolerates null values (unlike {@code Map.of},
     * which throws NullPointerException on null keys or values). Nulls are
     * serialized as JSON null.
     */
    private Map<String, Object> payloadMap(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String buildPackagePayload(PackageEntity pkg, NoticeEventType eventType,
                                       Long actorId, PackageStatus previousStatus) {
        try {
            return JSON.writeValueAsString(payloadMap(
                    "resourceType", "PACKAGE",
                    "resourceId", pkg.getId().toString(),
                    "trackingCode", pkg.getTrackingCode(),
                    "previousStatus", previousStatus != null ? previousStatus.name() : null,
                    "newStatus", pkg.getStatus().name(),
                    "actorId", actorId != null ? actorId.toString() : null,
                    "changedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to build package notice payload", e);
            return "{}";
        }
    }

    private String buildAssignmentObjectionPayload(PackageEntity pkg,
                                                   PackageAssignmentEntity assignment,
                                                   Long driverId,
                                                   String reason) {
        try {
            return JSON.writeValueAsString(payloadMap(
                    "resourceType", "PACKAGE",
                    "resourceId", pkg.getId().toString(),
                    "packageId", pkg.getId().toString(),
                    "trackingCode", pkg.getTrackingCode(),
                    "assignmentId", assignment.getId() != null ? assignment.getId().toString() : null,
                    "driverId", driverId != null ? driverId.toString() : null,
                    "assignedBy", assignment.getAssignedBy() != null ? assignment.getAssignedBy().toString() : null,
                    "assignedAt", assignment.getAssignedAt() != null ? assignment.getAssignedAt().toString() : null,
                    "objectionReason", reason,
                    "changedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to build assignment objection notice payload", e);
            return "{}";
        }
    }

    private String buildOfficeClaimPayload(PackageEntity pkg,
                                           OfficeClaimEntity claim,
                                           Long actorId,
                                           String detailKey,
                                           String detail) {
        try {
            return JSON.writeValueAsString(payloadMap(
                    "resourceType", "PACKAGE",
                    "resourceId", pkg.getId().toString(),
                    "packageId", pkg.getId().toString(),
                    "trackingCode", pkg.getTrackingCode(),
                    "claimId", claim.getId() != null ? claim.getId().toString() : null,
                    "driverId", claim.getDriverId() != null ? claim.getDriverId().toString() : null,
                    "claimedBy", claim.getClaimedBy() != null ? claim.getClaimedBy().toString() : null,
                    "officeLocationId", claim.getOfficeLocationId() != null ? claim.getOfficeLocationId().toString() : null,
                    "claimedAt", claim.getClaimedAt() != null ? claim.getClaimedAt().toString() : null,
                    "disputeDeadline", claim.getDisputeDeadline() != null ? claim.getDisputeDeadline().toString() : null,
                    detailKey, detail,
                    "actorId", actorId != null ? actorId.toString() : null,
                    "changedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to build office claim notice payload", e);
            return "{}";
        }
    }

    private String buildTransferPayload(TransferEntity transfer, NoticeEventType eventType,
                                        Long actorId, TransferStatus previousStatus,
                                        List<PackageEntity> packages) {
        try {
            var map = payloadMap(
                    "resourceType", "TRANSFER",
                    "resourceId", transfer.getId().toString(),
                    "previousStatus", previousStatus != null ? previousStatus.name() : null,
                    "newStatus", transfer.getStatus().name(),
                    "ruleType", transfer.getRuleType().name(),
                    "actorId", actorId != null ? actorId.toString() : null,
                    "changedAt", Instant.now().toString()
            );
            if (packages != null && !packages.isEmpty()) {
                var packageIds = packages.stream()
                        .map(p -> p.getId().toString())
                        .toList();
                var codes = packages.stream()
                        .map(PackageEntity::getTrackingCode)
                        .filter(code -> code != null && !code.isBlank())
                        .toList();
                map.put("packageId", packageIds.get(0));
                map.put("packageIds", String.join(", ", packageIds));
                if (!codes.isEmpty()) {
                    map.put("trackingCode", codes.get(0));
                    map.put("trackingCodes", String.join(", ", codes));
                }
            }
            return JSON.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to build transfer notice payload", e);
            return "{}";
        }
    }

    /**
     * Resolves the packages belonging to a transfer so transfer notifications
     * carry both package UUIDs and tracking codes.
     */
    private List<PackageEntity> resolveTransferPackages(UUID transferId) {
        var transferPackages = transferPackageRepo.findByTransferId(transferId);
        if (transferPackages.isEmpty()) {
            return List.of();
        }
        var ids = transferPackages.stream()
                .map(TransferPackageEntity::getPackageId)
                .toList();
        var byId = packageRepo.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(PackageEntity::getId,
                        java.util.function.Function.identity(), (first, second) -> first));
        return ids.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private String buildPackageAddedPayload(TransferEntity transfer, PackageEntity pkg, Long actorId) {
        try {
            return JSON.writeValueAsString(payloadMap(
                    "resourceType", "TRANSFER",
                    "resourceId", transfer.getId().toString(),
                    "packageId", pkg.getId().toString(),
                    "trackingCode", pkg.getTrackingCode(),
                    "addedBy", actorId != null ? actorId.toString() : null,
                    "changedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to build package-added payload", e);
            return "{}";
        }
    }

    private String buildDeliveryCodePayload(PackageEntity pkg, String deliveryCode, Long actorId, PackageStatus previousStatus) {
        try {
            return JSON.writeValueAsString(payloadMap(
                    "resourceType", "PACKAGE",
                    "resourceId", pkg.getId().toString(),
                    "trackingCode", pkg.getTrackingCode(),
                    "previousStatus", previousStatus != null ? previousStatus.name() : null,
                    "newStatus", pkg.getStatus().name(),
                    "deliveryCode", deliveryCode,
                    "actorId", actorId != null ? actorId.toString() : null,
                    "changedAt", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to build delivery code payload", e);
            return "{}";
        }
    }
}
