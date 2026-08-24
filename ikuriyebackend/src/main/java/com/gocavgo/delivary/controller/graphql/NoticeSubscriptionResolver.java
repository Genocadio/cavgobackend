package com.gocavgo.delivary.controller.graphql;

import com.gocavgo.delivary.service.subscription.NoticePublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

/**
 * GraphQL subscription resolver for real-time notice notifications.
 * <p>
 * The {@code noticeCreated} subscription delivers new notices to the
 * authenticated user. Events are filtered server-side so each subscriber
 * only receives notices addressed to them (via the notice_viewers row).
 */
@Controller
@RequiredArgsConstructor
public class NoticeSubscriptionResolver {

    private final NoticePublisher publisher;

    @SubscriptionMapping
    @PreAuthorize("isAuthenticated()")
    public Flux<NoticeResolver.NoticeResponse> noticeCreated() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var subscriberId = Long.parseLong(auth.getName());

        return publisher.getFlux()
                .filter(event -> event.userId().equals(subscriberId))
                .map(NoticePublisher.NoticeEvent::notice);
    }
}
