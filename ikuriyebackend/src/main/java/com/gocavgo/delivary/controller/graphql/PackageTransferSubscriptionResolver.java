package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.subscription.PackageTransferPublisher;
import com.gocavgo.delivary.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * GraphQL subscription resolver for real-time package+transfer notifications.
 * <p>
 * The {@code newPackageTransfer} subscription delivers newly created packages
 * (with an auto-created transfer) to eligible subscribers. Events are filtered
 * per-subscriber based on the transfer's targeting fields:
 * <ul>
 *   <li>{@code acceptorType} — DRIVER → only Driver role; WORKER → only Worker role; BOTH → all</li>
 *   <li>{@code matchUserId} — only the specified user</li>
 *   <li>{@code matchCompanyId} — only users belonging to that company</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
public class PackageTransferSubscriptionResolver {

    private final PackageTransferPublisher publisher;
    private final UserService userService;

    @SubscriptionMapping
    @PreAuthorize("isAuthenticated()")
    public Flux<PackageCreationResponse> newPackageTransfer() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var subscriberId = Long.parseLong(auth.getName());
        var subscriberRole = NexxauthRoles.primaryRole(auth.getAuthorities());

        // Only DRIVERS and WORKERS receive transfer events — ADMIN/SUPER_ADMIN/CUSTOMER are excluded.
        if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER) {
            return Flux.empty();
        }

        var subscriberCompanyId = userService.getCompanyIdForUser(subscriberId);

        return publisher.getFlux().filter(pc -> {
            var transfer = pc.transfer();
            if (transfer == null) return false;

            // ── matchUserId filter ──────────────────────────────────
            if (transfer.matchUserId() != null
                    && !transfer.matchUserId().equals(subscriberId)) {
                return false;
            }

            // ── acceptorType filter ──────────────────────────────────
            if (transfer.acceptorType() != null) {
                switch (transfer.acceptorType()) {
                    case DRIVER -> { if (subscriberRole != Role.DRIVER) return false; }
                    case WORKER -> { if (subscriberRole != Role.WORKER) return false; }
                    case BOTH -> {
                        if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER) return false;
                    }
                }
            }

            // ── matchCompanyId filter ────────────────────────────────
            if (transfer.matchCompanyId() != null) {
                if (subscriberCompanyId == null) return false;
                if (!transfer.matchCompanyId().equals(subscriberCompanyId)) return false;
            }

            return true;
        });
    }
}
