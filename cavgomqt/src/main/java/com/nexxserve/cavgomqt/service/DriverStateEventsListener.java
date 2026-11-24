package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Immediate driver-state triggers from cavgomain's existing fanout exchanges.
 *
 * Uses a dedicated queue per exchange so it cannot steal messages from the
 * adminaggregate consumers. The periodic reconciler remains the safety net for
 * missed events.
 */
@Service
public class DriverStateEventsListener {

    private static final Logger logger = LoggerFactory.getLogger(DriverStateEventsListener.class);

    @Autowired
    private DriverStateService driverStateService;

    @RabbitListener(queues = RabbitMQConfig.CAVGOMQT_DRIVER_EVENTS_QUEUE)
    public void handleDriverEvent(Map<String, Object> envelope) {
        try {
            if (envelope == null) return;
            String event = str(envelope.get("event"));
            Object rawData = envelope.get("data");
            if (!(rawData instanceof Map<?, ?>)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) rawData;

            Long driverId = firstLong(data, "driverId", "id");
            if (driverId == null) return;

            if ("DELETE".equalsIgnoreCase(event)) {
                driverStateService.applyIdentity(
                        driverId, "CLIENT", null, "ROLE_REVOKED", "DRIVER_EVENT_DELETE");
                return;
            }

            String role = str(data.get("role"));
            String status = str(data.get("status"));
            if (role != null && !"DRIVER".equalsIgnoreCase(role)) {
                driverStateService.applyIdentity(
                        driverId, role, status, "ROLE_REVOKED", "DRIVER_EVENT_ROLE_CHANGE");
                return;
            }
            if (role != null || status != null) {
                driverStateService.applyIdentity(
                        driverId,
                        role == null ? "DRIVER" : role,
                        status,
                        "ACTIVE",
                        "DRIVER_EVENT_UPDATE");
                return;
            }
            driverStateService.refreshForDriver(driverId, "DRIVER_EVENT_" + event);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to process driver event for driver-state: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = RabbitMQConfig.CAVGOMQT_VEHICLE_EVENTS_QUEUE)
    public void handleVehicleEvent(Map<String, Object> envelope) {
        try {
            if (envelope == null) return;
            String event = str(envelope.get("event"));
            Object rawData = envelope.get("data");
            if (!(rawData instanceof Map<?, ?>)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) rawData;

            // Vehicle payloads use "id" for the vehicle itself, so only the
            // explicit driverId or the embedded driver.id is authoritative.
            Long driverId = firstLong(data, "driverId");
            Object rawDriver = data.get("driver");
            if (driverId == null && rawDriver instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> driver = (Map<String, Object>) rawDriver;
                driverId = firstLong(driver, "id");
            }
            if (driverId == null) return;

            // A fresh assignment is an explicit reactivation after a demotion
            // or removal. Generic UPDATE events must not do this, because they
            // may be stale snapshots published before the demotion committed.
            if ("DRIVER_ASSIGNMENT".equalsIgnoreCase(event)) {
                String status = null;
                if (rawDriver instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> driver = (Map<String, Object>) rawDriver;
                    status = str(driver.get("status"));
                }
                driverStateService.applyIdentity(
                        driverId, "DRIVER", status, "ACTIVE", "VEHICLE_DRIVER_ASSIGNMENT");
                return;
            }

            driverStateService.refreshForDriver(driverId, "VEHICLE_EVENT_" + event);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to process vehicle event for driver-state: {}", e.getMessage());
        }
    }

    private Long firstLong(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value == null) continue;
            if (value instanceof Number) return ((Number) value).longValue();
            try {
                return Long.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // try next key
            }
        }
        return null;
    }

    private String str(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value);
        return result.isBlank() ? null : result;
    }
}
