package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.dto.transfer.output.TransferEvent;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.subscription.TransferCreatedPublisher;
import com.gocavgo.delivary.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GraphQL subscription resolver for real-time transfer-creation notifications.
 * <p>
 * The {@code transferCreated} subscription delivers newly created transfers
 * to eligible subscribers. Events are filtered per-subscriber based on the
 * transfer's targeting fields:
 * <ul>
 *   <li>{@code acceptorType} — DRIVER → only Driver role; WORKER → only Worker role; BOTH → all</li>
 *   <li>{@code matchUserId} — only the specified user</li>
 *   <li>{@code matchCompanyId} — only users belonging to that company</li>
 * </ul>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class TransferCreatedSubscriptionResolver {

    private final TransferCreatedPublisher publisher;
    private final UserService userService;

    @SubscriptionMapping
    public Flux<TransferEvent> transferCreated() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .switchIfEmpty(Mono.fromCallable(() -> SecurityContextHolder.getContext().getAuthentication())
                        .filter(auth -> auth != null && auth.isAuthenticated()))
                .switchIfEmpty(Mono.error(new AccessDeniedException("Authentication required for transferCreated subscription")))
                .flatMapMany(auth -> {
                    var subscriberId = Long.parseLong(auth.getName());
                    var subscriberRole = NexxauthRoles.primaryRole(auth.getAuthorities());

                    log.info("transferCreated subscription started for userId={}, role={}", subscriberId, subscriberRole);

                    if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER) {
                        log.info("transferCreated: userId={} role={} is not DRIVER/WORKER — returning empty stream",
                                subscriberId, subscriberRole);
                        return Flux.empty();
                    }

                    var subscriberCompanyId = userService.getCompanyIdForUser(subscriberId);

                    return publisher.getFlux().filter(event -> {
                        var transfer = event.transfer();
                        if (transfer == null) return false;

                        // matchUserId filter
                        if (transfer.matchUserId() != null
                                && !transfer.matchUserId().equals(subscriberId)) {
                            return false;
                        }

                        // acceptorType filter
                        if (transfer.acceptorType() != null) {
                            switch (transfer.acceptorType()) {
                                case DRIVER -> { if (subscriberRole != Role.DRIVER) return false; }
                                case WORKER -> { if (subscriberRole != Role.WORKER) return false; }
                                case BOTH -> {
                                    if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER) return false;
                                }
                            }
                        }

                        // matchCompanyId filter
                        if (transfer.matchCompanyId() != null) {
                            if (subscriberCompanyId == null) return false;
                            if (!transfer.matchCompanyId().equals(subscriberCompanyId)) return false;
                        }

                        return true;
                    });
                });
    }
}
