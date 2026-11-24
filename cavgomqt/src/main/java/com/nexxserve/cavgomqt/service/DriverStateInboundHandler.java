package com.nexxserve.cavgomqt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/** Receives ACK/report messages from Android and GPS vehicle reports. */
@Component
public class DriverStateInboundHandler {

    private static final Logger logger = LoggerFactory.getLogger(DriverStateInboundHandler.class);

    @Autowired
    private DriverStateService driverStateService;

    public void handle(Message<?> message) {
        String payload = String.valueOf(message.getPayload());
        String topic = String.valueOf(message.getHeaders().getOrDefault("mqtt_receivedTopic", ""));
        logger.info("📥 Driver-state inbound: {} ({} bytes)", topic, payload.length());
        driverStateService.handleClientMessage(topic, payload);
    }

    public void handleUserLocation(String userId, String vehicleId) {
        if (userId == null || userId.isBlank()) return;
        try {
            driverStateService.reportVehicleFromLocation(Long.valueOf(userId), Long.valueOf(vehicleId));
        } catch (NumberFormatException ignored) {
            logger.debug("Non-numeric driver/vehicle in user location report: user={} vehicle={}", userId, vehicleId);
        }
    }
}
