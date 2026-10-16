package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.dto.delivery.output.OfferClaimedEvent;
import com.gocavgo.delivary.enums.user.Role;
import com.gocavgo.delivary.security.NexxauthRoles;
import com.gocavgo.delivary.service.subscription.OfferClaimedPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GraphQL subscription resolver for offer claimed / accepted notifications.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class OfferClaimedSubscriptionResolver {

    private final OfferClaimedPublisher publisher;

    @SubscriptionMapping
    public Flux<OfferClaimedEvent> offerClaimed() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .switchIfEmpty(Mono.fromCallable(() -> SecurityContextHolder.getContext().getAuthentication())
                        .filter(auth -> auth != null && auth.isAuthenticated()))
                .switchIfEmpty(Mono.error(new AccessDeniedException("Authentication required for offerClaimed subscription")))
                .flatMapMany(auth -> {
                    var subscriberId = Long.parseLong(auth.getName());
                    var subscriberRole = NexxauthRoles.primaryRole(auth.getAuthorities());

                    log.info("offerClaimed subscription started for userId={}, role={}", subscriberId, subscriberRole);

                    if (subscriberRole != Role.DRIVER && subscriberRole != Role.WORKER
                            && subscriberRole != Role.ADMIN && subscriberRole != Role.SUPER_ADMIN) {
                        return Flux.empty();
                    }

                    return publisher.getFlux();
                });
    }
}
