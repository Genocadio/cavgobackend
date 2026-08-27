package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.service.subscription.NoticePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * GraphQL subscription resolver for real-time notice notifications.
 * <p>
 * The {@code noticeCreated} subscription delivers new notices to the
 * authenticated user. Events are filtered server-side so each subscriber
 * only receives notices addressed to them (via the notice_viewers row).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class NoticeSubscriptionResolver {

    private final NoticePublisher publisher;

    @SubscriptionMapping
    public Flux<NoticeResolver.NoticeResponse> noticeCreated() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth != null && auth.isAuthenticated())
                .switchIfEmpty(Mono.fromCallable(() -> SecurityContextHolder.getContext().getAuthentication())
                        .filter(auth -> auth != null && auth.isAuthenticated()))
                .switchIfEmpty(Mono.error(new AccessDeniedException("Authentication required for noticeCreated subscription")))
                .flatMapMany(auth -> {
                    var subscriberId = Long.parseLong(auth.getName());
                    log.info("noticeCreated subscription active for subscriberId={}", subscriberId);
                    return publisher.getFlux()
                            .filter(event -> event.userId().equals(subscriberId))
                            .map(NoticePublisher.NoticeEvent::notice);
                });
    }
}
