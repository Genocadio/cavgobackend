package com.gocavgo.delivary.service.subscription;

import com.gocavgo.delivary.dto.transfer.output.TransferEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot-publisher for transfer-creation events.
 * <p>
 * When a transfer is created (via {@code createTransfer}), the service
 * publishes a {@link TransferEvent} here. GraphQL subscription resolvers
 * subscribe and filter per-subscriber based on the transfer's
 * {@code acceptorType}, {@code matchUserId}, and {@code matchCompanyId}.
 */
@Service
public class TransferCreatedPublisher {

    private final Sinks.Many<TransferEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void publish(TransferEvent event) {
        sink.tryEmitNext(event);
    }

    public Flux<TransferEvent> getFlux() {
        return sink.asFlux();
    }

    @PreDestroy
    public void complete() {
        sink.tryEmitComplete();
    }
}
