package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.subscription.PackageTransferPublisher;
import com.gocavgo.delivary.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

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
@Slf4j
@Controller
@RequiredArgsConstructor
public class PackageTransferSubscriptionResolver {

    private final PackageTransferPublisher publisher;
    private final UserService userService;

    @SubscriptionMapping
    public Flux<PackageCreationResponse> newPackageTransfer() {
        // In Spring Boot 4.x, SecurityContextHolder may be empty on WebSocket
        // threads.  The auth token is sent in the connection_init payload and
        // in the HTTP upgrade headers — try both paths.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("newPackageTransfer subscription: no authentication in SecurityContext — " +
                     "rejecting subscription. Ensure the client sends Authorization header in the WebSocket upgrade request.");
            return Flux.error(new AccessDeniedException("Authentication required for subscription"));
        }

        var subscriberId = Long.parseLong(auth.getName());
        var subscriberRole = NexxauthRoles.primaryRole(auth.getAuthorities());

        log.info("newPackageTransfer subscription started for userId={}, role={}", subscriberId, subscriberRole);

        // Only DRIVERS and WORKERS receive transfer events — ADMIN/SUPER_ADMIN/CUSTOMER are excluded.
        if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER) {
            log.info("newPackageTransfer subscription: userId={} role={} is not DRIVER/WORKER — returning empty stream",
                     subscriberId, subscriberRole);
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
