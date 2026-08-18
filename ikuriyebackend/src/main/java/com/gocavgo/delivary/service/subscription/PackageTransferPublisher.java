package com.gocavgo.delivary.service.subscription;

import com.gocavgo.delivary.dto.delivery.output.PackageCreationResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot-publisher for new package+transfer creation events.
 * <p>
 * When a package is created with an auto-created transfer (via {@code createPackage}
 * with a {@code transferRuleType}), the service publishes the resulting
 * {@link PackageCreationResponse} here. GraphQL subscription resolvers subscribe
 * to this publisher and filter events per-subscriber based on the transfer's
 * {@code acceptorType}, {@code matchUserId}, and {@code matchCompanyId} fields.
 * </p>
 * <p>
 * Uses a multicast sink so each new subscriber receives only future events
 * (no replay of past events).
 * </p>
 */
@Service
public class PackageTransferPublisher {

    private final Sinks.Many<PackageCreationResponse> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Publish a new package+transfer event to all active subscribers.
     * Called from inside the same transaction as the package/transfer write.
     */
    public void publish(PackageCreationResponse event) {
        sink.tryEmitNext(event);
    }

    /**
     * Returns a shared Flux that emits all published events.
     * Each caller (each WebSocket subscriber) receives the same stream
     * but may apply independent filtering.
     */
    public Flux<PackageCreationResponse> getFlux() {
        return sink.asFlux();
    }

    @PreDestroy
    public void complete() {
        sink.tryEmitComplete();
    }
}
