package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.dto.delivery.output.DeliveryRatingResponse;
import com.gocavgo.delivary.entity.delivery.DeliveryRatingEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.delivery.PackageEventEntity;
import com.gocavgo.delivary.enums.delivery.CustodianRole;
import com.gocavgo.delivary.enums.delivery.PackageEventType;
import com.gocavgo.delivary.enums.delivery.PackageStatus;
import com.gocavgo.delivary.enums.delivery.PersonRole;
import com.gocavgo.delivary.exception.BusinessValidationException;
import com.gocavgo.delivary.repository.delivery.DeliveryRatingJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCarrierLegJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageCustodianJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageEventJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackageJpaRepository;
import com.gocavgo.delivary.repository.delivery.PackagePersonJpaRepository;
import com.gocavgo.delivary.util.DeliveryWindowUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Post-delivery ratings submitted by a package's sender or receiver. */
@Service
@RequiredArgsConstructor
public class DriverRatingService {

    private static final Logger log = LoggerFactory.getLogger(DriverRatingService.class);
    private static final int MAX_COMMENT_LENGTH = 500;

    private final PackageJpaRepository packageRepo;
    private final PackagePersonJpaRepository personRepo;
    private final PackageCarrierLegJpaRepository carrierLegRepo;
    private final PackageCustodianJpaRepository custodianRepo;
    private final PackageEventJpaRepository eventRepo;
    private final DeliveryRatingJpaRepository ratingRepo;

    @Transactional
    public DeliveryRatingResponse rateDelivery(Long clientId, UUID packageId, int score, String comment) {
        return rateDelivery(clientId, packageId, score, comment, Instant.now());
    }

    @Transactional
    public DeliveryRatingResponse rateDelivery(Long clientId, UUID packageId, int score, String comment,
                                               Instant now) {
        if (clientId == null) {
            throw new BusinessValidationException("Authentication required");
        }
        if (score < 1 || score > 5) {
            throw new BusinessValidationException("Rating score must be between 1 and 5");
        }
        var cleanComment = comment == null ? null : comment.trim();
        if (cleanComment != null && cleanComment.length() > MAX_COMMENT_LENGTH) {
            throw new BusinessValidationException("Rating comment must be 500 characters or fewer");
        }

        var pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new BusinessValidationException("Package not found: " + packageId));
        if (pkg.getStatus() != PackageStatus.DELIVERED && pkg.getStatus() != PackageStatus.COMPLETED) {
            throw new BusinessValidationException("Only delivered packages can be rated");
        }

        var deliveredAt = resolveDeliveredAt(pkg);
        if (!DeliveryWindowUtil.isWithin(deliveredAt, now, DeliveryWindowUtil.RATING_WINDOW)) {
            throw new BusinessValidationException("Ratings are only accepted within 30 days of delivery");
        }

        var driverId = resolveDriverId(pkg);
        if (driverId == null) {
            throw new BusinessValidationException("This package has no identifiable delivering driver");
        }
        if (driverId.equals(clientId)) {
            throw new BusinessValidationException("A driver cannot rate their own delivery");
        }
        if (!isClientForPackage(pkg, clientId)) {
            throw new BusinessValidationException("Only the sender or receiver can rate this delivery");
        }
        if (ratingRepo.existsByPackageIdAndClientId(packageId, clientId)) {
            throw new BusinessValidationException("You have already rated this delivery");
        }

        var entity = DeliveryRatingEntity.builder()
                .packageId(packageId)
                .driverId(driverId)
                .clientId(clientId)
                .score(score)
                .comment(cleanComment.isBlank() ? null : cleanComment)
                .createdAt(now)
                .build();
        try {
            entity = ratingRepo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessValidationException("You have already rated this delivery");
        }
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public DeliveryRatingResponse getMyRating(UUID packageId, Long clientId) {
        if (clientId == null) return null;
        return ratingRepo.findByPackageIdAndClientId(packageId, clientId)
                .map(this::toResponse)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Double getDriverAverageRating(Long driverId) {
        if (driverId == null) return null;
        var count = ratingRepo.countByDriverId(driverId);
        return count == 0 ? null : ratingRepo.averageScoreByDriverId(driverId);
    }

    @Transactional(readOnly = true)
    public long getDriverRatingCount(Long driverId) {
        return driverId == null ? 0 : ratingRepo.countByDriverId(driverId);
    }

    @Transactional(readOnly = true)
    public Double getPackageDriverAverageRating(UUID packageId) {
        return getDriverAverageRating(resolveDriverId(packageId));
    }

    @Transactional(readOnly = true)
    public long getPackageDriverRatingCount(UUID packageId) {
        return getDriverRatingCount(resolveDriverId(packageId));
    }

    private boolean isClientForPackage(PackageEntity pkg, Long clientId) {
        if (pkg.getCreatorId() != null && pkg.getCreatorId().equals(clientId)) return true;
        return personRepo.findByPackageId(pkg.getId()).stream()
                .anyMatch(person -> person.getUserId() != null
                        && person.getUserId().equals(clientId)
                        && (person.getRole() == PersonRole.SENDER || person.getRole() == PersonRole.RECEIVER));
    }

    /** The actual delivering driver, including FIXED_ROUTE office handoffs. */
    private Long resolveDriverId(UUID packageId) {
        return carrierLegRepo.findByPackageIdOrderByLinkedAtDesc(packageId).stream()
                .findFirst()
                .map(leg -> leg.getDriverId())
                .orElseGet(() -> custodianRepo.findByPackageIdAndRole(packageId, CustodianRole.DRIVER).stream()
                        .findFirst()
                        .map(c -> c.getUserId())
                        .orElse(null));
    }

    private Long resolveDriverId(PackageEntity pkg) {
        return resolveDriverId(pkg.getId());
    }

    private Instant resolveDeliveredAt(PackageEntity pkg) {
        if (pkg.getDeliveredAt() != null) return pkg.getDeliveredAt();
        return eventRepo.findFirstByPackageIdAndEventTypeOrderByCreatedAtDesc(pkg.getId(), PackageEventType.DELIVERED)
                .map(PackageEventEntity::getCreatedAt)
                .orElse(pkg.getUpdatedAt());
    }

    private DeliveryRatingResponse toResponse(DeliveryRatingEntity entity) {
        return new DeliveryRatingResponse(
                entity.getId(), entity.getPackageId(), entity.getScore(),
                entity.getComment(), entity.getCreatedAt());
    }
}
