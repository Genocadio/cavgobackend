package com.gocavgo.delivary.controller.delivery;

import com.gocavgo.delivary.dto.delivery.output.DeliveryRatingResponse;
import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.security.SecurityUtils;
import com.gocavgo.delivary.service.delivery.DriverRatingService;
import com.gocavgo.delivary.service.delivery.PackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

/** GraphQL surface for proof-of-delivery photos and driver ratings. */
@Controller
@RequiredArgsConstructor
public class DeliveryFeedbackResolver {

    private final PackageService packageService;
    private final DriverRatingService ratingService;

    @MutationMapping
    @PreAuthorize("hasAnyRole('DRIVER','WORKER')")
    public PackageResponse attachDeliveryProofPhoto(@Argument UUID packageId,
                                                    @Argument String proofPhotoUrl) {
        var actorId = SecurityUtils.getCurrentUserId();
        if (actorId == null) {
            throw new com.gocavgo.delivary.exception.BusinessValidationException("Authentication required");
        }
        return packageService.attachDeliveryProofPhoto(actorId, packageId, proofPhotoUrl);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public DeliveryRatingResponse rateDelivery(@Argument UUID packageId,
                                               @Argument int score,
                                               @Argument String comment) {
        var actorId = SecurityUtils.getCurrentUserId();
        if (actorId == null) {
            throw new com.gocavgo.delivary.exception.BusinessValidationException("Authentication required");
        }
        return ratingService.rateDelivery(actorId, packageId, score, comment);
    }

    /**
     * Masks proof photos at read time. The full PackageResponse is also returned
     * by the unauthenticated packageByTrackingCode lookup, so the DTO URL must
     * never be exposed directly by GraphQL.
     */
    @SchemaMapping(typeName = "DeliveryPackage", field = "proofPhotoUrl")
    public String proofPhotoUrl(PackageResponse pkg) {
        if (pkg.proofPhotoUrl() == null) return null;
        if (!packageService.canViewProofPhoto(
                pkg.id(), SecurityUtils.getCurrentUserId(), SecurityUtils.getCurrentUserRole())) {
            return null;
        }
        return pkg.proofPhotoUrl();
    }

    @SchemaMapping(typeName = "DeliveryPackage", field = "myRating")
    public DeliveryRatingResponse myRating(PackageResponse pkg) {
        return ratingService.getMyRating(pkg.id(), SecurityUtils.getCurrentUserId());
    }

    @SchemaMapping(typeName = "DeliveryPackage", field = "driverAverageRating")
    public Double driverAverageRating(PackageResponse pkg) {
        return ratingService.getPackageDriverAverageRating(pkg.id());
    }

    @SchemaMapping(typeName = "DeliveryPackage", field = "driverRatingCount")
    public long driverRatingCount(PackageResponse pkg) {
        return ratingService.getPackageDriverRatingCount(pkg.id());
    }

    @SchemaMapping(typeName = "User", field = "averageRating")
    public Double averageRating(UserResponse user) {
        if (user.role() != Role.DRIVER) return null;
        return ratingService.getDriverAverageRating(user.id());
    }

    @SchemaMapping(typeName = "User", field = "ratingCount")
    public long ratingCount(UserResponse user) {
        if (user.role() != Role.DRIVER) return 0;
        return ratingService.getDriverRatingCount(user.id());
    }
}
