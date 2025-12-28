package com.gocavgo.Navigation.service;

import com.gocavgo.Navigation.config.RabbitConfig;
import com.gocavgo.Navigation.model.dto.TripResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TripEventPublisher {
    private final RabbitTemplate rabbitTemplate;

    public void publishTripUpdate(TripResponse response) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.TRIP_UPDATE_EXCHANGE, "", response);
            log.info("Published trip update to exchange {} for tripId: {} with status: {}",
                    RabbitConfig.TRIP_UPDATE_EXCHANGE,
                    response.getTrip() != null ? response.getTrip().getId() : null,
                    response.getTrip() != null ? response.getTrip().getStatus() : null);
        } catch (Exception ex) {
            log.error("Failed to publish trip update to exchange {} for tripId: {}", RabbitConfig.TRIP_UPDATE_EXCHANGE,
                    response.getTrip() != null ? response.getTrip().getId() : null, ex);
        }
    }
}
