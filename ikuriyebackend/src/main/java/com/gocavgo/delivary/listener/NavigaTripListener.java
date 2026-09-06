package com.gocavgo.delivary.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.delivary.entity.naviga.NavigaTripEntity;
import com.gocavgo.delivary.repository.naviga.NavigaTripJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Listens on the {@code cavgomqt.trip.updates} fanout exchange (via bound queue
 * {@code ikuriyebackend.naviga-trips}) and stores/updates/deletes Naviga trip records.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>naviga-trip-create → upsert the trip row</li>
 *   <li>naviga-gps-batch → update waypoint progress + current location</li>
 *   <li>COMPLETED → set expires_at = now + 10 hours</li>
 *   <li>naviga-trip-delete or DELETED → delete immediately</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NavigaTripListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long COMPLETION_EXPIRY_HOURS = 10;

    private final NavigaTripJpaRepository tripRepository;

    @RabbitListener(
            queues = "${rabbitmq.naviga.queue:ikuriyebackend.naviga-trips}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onNavigaTripEvent(Map<String, Object> rawEvent) {
        try {
            String source = getString(rawEvent, "source");
            Map<String, Object> trip = getMap(rawEvent, "trip");
            if (trip == null) {
                log.warn("[NavigaTrip] Received event with null trip data, source={}", source);
                return;
            }

            Long tripId = getLong(trip, "id");
            if (tripId == null) {
                log.warn("[NavigaTrip] Trip ID is null, skipping");
                return;
            }

            String status = getString(trip, "status");
            String carId = getString(trip, "carId");
            Instant eventTimestamp = parseInstant(rawEvent.get("timestamp"));
            Instant navigaCreatedAt = parseInstant(trip.get("createdAt"));
            Instant navigaCompletedAt = parseInstant(trip.get("completedAt"));

            log.info("[NavigaTrip] Received event: tripId={}, status={}, carId={}, source={}",
                    tripId, status, carId, source);

            // --- DELETED → delete immediately ---
            if ("DELETED".equals(status) || "naviga-trip-delete".equals(source)) {
                if (tripRepository.existsById(tripId)) {
                    tripRepository.deleteById(tripId);
                    log.info("[NavigaTrip] Deleted trip {} (status={}, source={})", tripId, status, source);
                }
                return;
            }

            // --- Upsert the trip row ---
            NavigaTripEntity entity = tripRepository.findById(tripId).orElseGet(() ->
                    NavigaTripEntity.builder()
                            .navigaTripId(tripId)
                            .build()
            );

            entity.setCarId(carId);
            entity.setStatus(status);
            entity.setNavigaCreatedAt(navigaCreatedAt);
            entity.setNavigaCompletedAt(navigaCompletedAt);
            entity.setEventTimestamp(eventTimestamp);
            entity.setSource(source);

            // Store waypoint progress as JSON
            Object wpObj = trip.get("waypointProgresses");
            if (wpObj != null) {
                entity.setWaypointProgressesJson(MAPPER.writeValueAsString(wpObj));
            }

            // Store current location as JSON
            Object locObj = trip.get("currentLocation");
            if (locObj != null) {
                entity.setCurrentLocationJson(MAPPER.writeValueAsString(locObj));
            }

            // COMPLETED → set expiry to now + 10 hours
            if ("COMPLETED".equals(status)) {
                entity.setExpiresAt(Instant.now().plus(COMPLETION_EXPIRY_HOURS, ChronoUnit.HOURS));
                log.info("[NavigaTrip] Trip {} COMPLETED — will auto-expire at {}",
                        tripId, entity.getExpiresAt());
            } else {
                // For non-completed trips, clear any leftover expiry
                entity.setExpiresAt(null);
            }

            tripRepository.save(entity);
            log.debug("[NavigaTrip] Saved trip {} (status={}, source={})", tripId, status, source);

        } catch (Exception e) {
            log.error("[NavigaTrip] Failed to process event: {}", e.getMessage(), e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Map) return (Map<String, Object>) val;
        return null;
    }

    private Instant parseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            return Instant.parse(s);
        }
        if (value instanceof Number n) {
            // Could be epoch millis or epoch seconds
            long ms = n.longValue();
            if (ms > 1e12) {
                return Instant.ofEpochMilli(ms);
            } else {
                return Instant.ofEpochSecond(ms);
            }
        }
        return null;
    }
}
