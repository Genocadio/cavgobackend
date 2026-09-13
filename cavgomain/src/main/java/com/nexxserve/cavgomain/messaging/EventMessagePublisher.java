package com.nexxserve.cavgomain.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Pushes vehicle/driver lifecycle events to the {@code vehicle.events} and
 * {@code driver.events} fanout exchanges. Downstream consumers (adminaggregate)
 * use these to keep their vehicle/driver/assignment store up to date in
 * real time (shape documented in RABBITMQ_MESSAGE_FORMAT.md).
 *
 * <p>Fanout exchanges ignore the routing key, so an empty key is used.</p>
 */
@Component
@RequiredArgsConstructor
public class EventMessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(EventMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;

    /**
     * Envelope published as {@code {"event": "...", "data": {...}}}.
     */
    public record Envelope(String event, Object data) {}

    public void publishVehicleEvent(String event, Object data) {
        publish("vehicle.events", event, data);
    }

    public void publishDriverEvent(String event, Object data) {
        publish("driver.events", event, data);
    }

    private void publish(String exchange, String event, Object data) {
        try {
            rabbitTemplate.convertAndSend(exchange, "", new Envelope(event, data));
        } catch (Exception e) {
            // Never let a failed publish break the primary flow.
            log.error("Failed to publish {} event to exchange {}: {}", event, exchange, e.getMessage());
        }
    }
}