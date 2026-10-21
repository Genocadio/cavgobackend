package com.gocavgo.delivary.service.subscription;

import com.gocavgo.delivary.dto.delivery.output.OfferClaimedEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot-publisher for offer claimed / accepted events.
 * Broadcasts in real-time to all connected drivers and workers watching the offers pool.
 */
@Service
public class OfferClaimedPublisher {

    private final Sinks.Many<OfferClaimedEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public synchronized void publish(OfferClaimedEvent event) {
        sink.tryEmitNext(event);
    }

    public Flux<OfferClaimedEvent> getFlux() {
        return sink.asFlux();
    }

    @PreDestroy
    public void complete() {
        sink.tryEmitComplete();
    }
}
