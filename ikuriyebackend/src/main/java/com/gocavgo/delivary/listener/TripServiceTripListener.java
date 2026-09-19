package com.gocavgo.delivary.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.delivary.entity.trip.CavgoTripEntity;
import com.gocavgo.delivary.repository.trip.CavgoTripJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Listens on the {@code tripservice.trips.updates} fanout exchange (via bound queue
 * {@code ikuriyebackend.cavgotrips-trips}) and mirrors trips published by the cavgotrips
 * trip service.
 *
 * <p>Message format is {@code {event, data: Trip}} produced by cavgotrips. Only active trips are kept:
 * <ul>
 *   <li>SCHEDULED / IN_PROGRESS → upsert the trip row (kept while active)</li>
 *   <li>COMPLETED → kept briefly, then auto-deleted after 1 hour</li>
 *   <li>CANCELLED / NOT_COMPLETED / deleted → delete the row immediately (even if already saved)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripServiceTripListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long COMPLETION_EXPIRY_HOURS = 1;

    private final CavgoTripJpaRepository tripRepository;

    @RabbitListener(
            queues = "${rabbitmq.cavgotrips.queue:ikuriyebackend.cavgotrips-trips}",
            containerFactory = "rabbitListenerContainerFactory"
    )
    public void onCavgoTripEvent(Map<String, Object> rawEvent) {
        try {
            JsonNode root = MAPPER.valueToTree(rawEvent);
            String event = root.path("event").asText(null);
            JsonNode trip = root.path("data");

            if (trip.isMissingNode() || trip.isNull()) {
                log.warn("[CavgoTrip] Received event without trip data, event={}", event);
                return;
            }

            Long tripId = trip.path("id").asLong();
            if (tripId == null || tripId <= 0) {
                log.warn("[CavgoTrip] Trip ID is missing, event={}", event);
                return;
            }

            String status = trip.path("status").asText(null);

            // --- deleted → delete immediately ---
            if ("deleted".equalsIgnoreCase(event)) {
                deleteTrip(tripId, event, "event");
                return;
            }

            // --- Cancelled / not-completed → do not keep, delete any saved row ---
            if ("CANCELLED".equalsIgnoreCase(status) || "NOT_COMPLETED".equalsIgnoreCase(status)) {
                deleteTrip(tripId, status, "status");
                return;
            }

            // --- Only active trips are stored ---
            boolean completed = "COMPLETED".equalsIgnoreCase(status);
            if (!completed && !"SCHEDULED".equalsIgnoreCase(status) && !"IN_PROGRESS".equalsIgnoreCase(status)) {
                log.debug("[CavgoTrip] Ignoring trip {} with unsupported status {}", tripId, status);
                return;
            }

            // --- Upsert the trip row ---
            CavgoTripEntity entity = tripRepository.findById(tripId).orElseGet(() ->
                    CavgoTripEntity.builder()
                            .tripId(tripId)
                            .build()
            );

            entity.setStatus(status);
            entity.setEvent(event);

            entity.setRouteId(longOrNull(trip, "route_id"));
            entity.setVehicleId(longOrNull(trip, "vehicle_id"));

            JsonNode vehicle = trip.get("vehicle");
            if (vehicle != null && !vehicle.isNull()) {
                entity.setCompanyId(longOrNull(vehicle, "company_id"));
                JsonNode driver = vehicle.get("driver");
                if (driver != null && !driver.isNull()) {
                    entity.setDriverId(longOrNull(driver, "id"));
                    entity.setDriverName(driver.path("name").asText(null));
                    entity.setDriverPhone(driver.path("phone").asText(null));
                }
                entity.setVehicleJson(MAPPER.writeValueAsString(vehicle));
            }

            entity.setDepartureTime(parseInstant(trip.get("departure_time")));
            entity.setCompletionTime(parseInstant(trip.get("completion_time")));
            entity.setConnectionMode(trip.path("connection_mode").asText(null));
            entity.setNotes(trip.path("notes").asText(null));
            entity.setSeats(intOrNull(trip, "seats"));
            entity.setRemainingSeats(intOrNull(trip, "remaining_seats"));
            entity.setPrice(doubleOrNull(trip, "price"));
            entity.setRemainingTimeToDestination(longOrNull(trip, "remaining_time_to_destination"));
            entity.setRemainingDistanceToDestination(doubleOrNull(trip, "remaining_distance_to_destination"));
            entity.setIsReversed(boolOrFalse(trip, "is_reversed"));
            entity.setHasCustomWaypoints(boolOrFalse(trip, "has_custom_waypoints"));
            entity.setAutoReturn(boolOrFalse(trip, "auto_return"));
            entity.setCurrentSpeed(doubleOrNull(trip, "current_speed"));
            entity.setCurrentLatitude(doubleOrNull(trip, "current_latitude"));
            entity.setCurrentLongitude(doubleOrNull(trip, "current_longitude"));

            entity.setTripCreatedAt(parseInstant(trip.get("created_at")));
            entity.setTripUpdatedAt(parseInstant(trip.get("updated_at")));

            JsonNode route = trip.get("route");
            if (route != null && !route.isNull()) {
                entity.setRouteJson(MAPPER.writeValueAsString(route));
            }
            JsonNode waypoints = trip.get("waypoints");
            if (waypoints != null && !waypoints.isNull()) {
                entity.setWaypointsJson(MAPPER.writeValueAsString(waypoints));
            }

            if (completed) {
                entity.setExpiresAt(Instant.now().plus(COMPLETION_EXPIRY_HOURS, ChronoUnit.HOURS));
                log.info("[CavgoTrip] Trip {} COMPLETED — will auto-expire at {}",
                        tripId, entity.getExpiresAt());
            } else {
                // Scheduled/in-progress trips are kept while active; clear any leftover expiry
                entity.setExpiresAt(null);
            }

            entity.setEventTimestamp(Instant.now());
            tripRepository.save(entity);
            log.debug("[CavgoTrip] Saved trip {} (status={}, event={})", tripId, status, event);

        } catch (Exception e) {
            log.error("[CavgoTrip] Failed to process event: {}", e.getMessage(), e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void deleteTrip(Long tripId, String reason, String reasonType) {
        if (tripRepository.existsById(tripId)) {
            tripRepository.deleteById(tripId);
            log.info("[CavgoTrip] Deleted trip {} ({}={})", tripId, reasonType, reason);
        } else {
            log.debug("[CavgoTrip] Ignoring trip {} ({}={}) — not stored", tripId, reasonType, reason);
        }
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isNumber()) return value.asLong();
        return null;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isNumber()) return value.asInt();
        return null;
    }

    private Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isNumber()) return value.asDouble();
        return null;
    }

    private boolean boolOrFalse(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() && value.asBoolean();
    }

    private Instant parseInstant(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isTextual()) {
            try {
                return Instant.parse(value.asText());
            } catch (Exception e) {
                return null;
            }
        }
        if (value.isNumber()) {
            long ts = value.asLong();
            if (ts > 1e12) {
                return Instant.ofEpochMilli(ts);
            } else {
                return Instant.ofEpochSecond(ts);
            }
        }
        return null;
    }
}