package com.nexxserve.cavgomain.messaging;

import com.nexxserve.cavgomain.config.RabbitMQConfig;
import com.nexxserve.cavgomain.dto.message.VehicleSettingsMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleSettingsPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishSettingsUpdate(VehicleSettingsMessage message, Long vehicleId) {
        try {
            String routingKey = RabbitMQConfig.VEHICLE_SETTINGS_ROUTING_KEY_PREFIX + vehicleId;
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.VEHICLE_SETTINGS_EXCHANGE,
                    routingKey,
                    message
            );
            log.info("Published settings update for vehicle ID: {} (license plate: {}) with routing key: {}", 
                    vehicleId, message.getLicensePlate(), routingKey);
        } catch (Exception e) {
            log.error("Error publishing settings update for vehicle ID: {} (license plate: {})", 
                    vehicleId, message.getLicensePlate(), e);
        }
    }
}

