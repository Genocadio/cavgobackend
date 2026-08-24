package com.gocavgo.delivary.service.subscription;

import com.gocavgo.delivary.controller.graphql.NoticeResolver.NoticeResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot-publisher for new notice events.
 * <p>
 * When a notice is created and viewer rows are persisted, the service publishes
 * a {@link NoticeEvent} here. GraphQL subscription resolvers subscribe and
 * filter events per-subscriber based on the viewer's {@code userId}.
 * </p>
 * <p>
 * Uses a multicast sink so each new subscriber receives only future events
 * (no replay of past events).
 * </p>
 */
@Service
public class NoticePublisher {

    private final Sinks.Many<NoticeEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Publish a notice event to all active subscribers.
     * Called from inside the same transaction as the notice/viewer write.
     */
    public void publish(NoticeEvent event) {
        sink.tryEmitNext(event);
    }

    /**
     * Returns a shared Flux that emits all published events.
     * Each caller (each WebSocket subscriber) receives the same stream
     * but may apply independent filtering.
     */
    public Flux<NoticeEvent> getFlux() {
        return sink.asFlux();
    }

    @PreDestroy
    public void complete() {
        sink.tryEmitComplete();
    }

    /**
     * A notice event carrying the viewer's userId and the full notice response.
     * The subscription resolver filters by userId so each subscriber only
     * receives notices addressed to them.
     */
    public record NoticeEvent(Long userId, NoticeResponse notice) {}
}
