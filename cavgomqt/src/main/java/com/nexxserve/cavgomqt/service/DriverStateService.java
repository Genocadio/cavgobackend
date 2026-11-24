package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.VehicleDto;
import com.nexxserve.cavgomqt.entity.DriverStateAckEntity;
import com.nexxserve.cavgomqt.entity.DriverStateEntity;
import com.nexxserve.cavgomqt.repository.DriverStateAckRepository;
import com.nexxserve.cavgomqt.repository.DriverStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authoritative, versioned driver configuration channel.
 *
 * Every meaningful vehicle/trip/identity change bumps a revision, publishes a
 * retained snapshot to drivers/{driverId}/state, and keeps republishing with
 * bounded backoff until each device ACKs. Client reports/ACKs that describe an
 * older vehicle or trip put that device back into emit mode.
 */
@Service
public class DriverStateService {

    private static final Logger logger = LoggerFactory.getLogger(DriverStateService.class);
    private static final long[] RETRY_BACKOFF_MS = {0L, 1_000L, 2_000L, 5_000L, 15_000L, 30_000L};

    @Autowired
    private DriverStateRepository stateRepository;

    @Autowired
    private DriverStateAckRepository ackRepository;

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    @Autowired
    private TripAssignmentReconciliationService tripAssignmentReconciliationService;

    @Autowired
    private MqttService mqttService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Single reconciliation loop: refreshes snapshots from cavgomain/cavgotrips
     * and retries any revision a device has not acknowledged.
     */
    @Scheduled(
            fixedDelayString = "${driver.state.reconcile.interval.ms:10000}",
            initialDelayString = "${driver.state.reconcile.initial.delay.ms:30000}")
    public void reconcile() {
        try {
            Map<Long, VehicleDto> vehiclesByDriver = vehicleRegistryService.findVehiclesByDriver();
            List<JsonNode> activeTrips = tripAssignmentReconciliationService.fetchActiveTrips();

            Set<Long> driverIds = new HashSet<>(vehiclesByDriver.keySet());
            stateRepository.findAll().forEach(state -> driverIds.add(state.getDriverId()));

            for (Long driverId : driverIds) {
                if (driverId == null) continue;
                refresh(driverId, "PERIODIC_RECONCILE", vehiclesByDriver, activeTrips);
            }

            long now = System.currentTimeMillis();
            for (DriverStateEntity state : stateRepository.findAll()) {
                publishIfDue(state, now);
            }
        } catch (Exception e) {
            logger.error("❌ Driver-state reconciliation failed: {}", e.getMessage(), e);
        }
    }

    public void refreshForDriver(Long driverId, String reason) {
        if (driverId == null) return;
        refresh(driverId, reason == null ? "DRIVER_REFRESH" : reason, null, null);
    }

    public void refreshForTrip(Trip trip, String event) {
        if (trip == null || trip.getVehicle() == null || trip.getVehicle().getDriver() == null) return;
        Integer driverId = trip.getVehicle().getDriver().getId();
        if (driverId == null || driverId <= 0) return;
        refresh(driverId.longValue(), "TRIP_" + String.valueOf(event).toUpperCase(), null, null);
    }

    /** Called by internal services when auth role/status/session actually changes. */
    public void applyIdentity(Long driverId, String role, String userStatus, String sessionState, String reason) {
        if (driverId == null) return;
        DriverStateEntity state = stateRepository.findById(driverId).orElseGet(this::newState);
        state.setDriverId(driverId);
        state.setRole(normalize(role, state.getRole(), "DRIVER"));
        state.setUserStatus(normalize(userStatus, state.getUserStatus(), "ACTIVE"));
        state.setSessionState(normalize(sessionState, state.getSessionState(), "ACTIVE"));
        stateRepository.save(state);
        refresh(driverId, reason == null ? "IDENTITY_CHANGED" : reason, null, null);
    }

    /**
     * Handles drivers/{driverId}/state/ack and /report. A stale report puts only
     * that device back into emit mode; other devices are unaffected.
     */
    public void handleClientMessage(String topic, String payload) {
        if (payload == null || payload.isBlank()) return;
        try {
            JsonNode message = objectMapper.readTree(payload);
            Long topicDriverId = extractDriverIdFromTopic(topic);
            Long driverId = firstLong(message, "driverId", topicDriverId);
            if (driverId == null) return;

            DriverStateEntity state = stateRepository.findById(driverId).orElse(null);
            if (state == null) return;

            String rawDeviceId = text(message, "deviceId");
            final String deviceId = (rawDeviceId == null || rawDeviceId.isBlank()) ? "unknown-device" : rawDeviceId;

            long revision = firstLong(message, "revision", 0L);
            boolean isAck = topic != null && topic.endsWith("/ack");
            final String key = driverId + ":" + deviceId;
            DriverStateAckEntity ack = ackRepository.findById(key).orElseGet(() -> {
                DriverStateAckEntity created = new DriverStateAckEntity();
                created.setId(key);
                created.setDriverId(driverId);
                created.setDeviceId(deviceId);
                created.setLastAckedRevision(0L);
                created.setLastReportedRevision(0L);
                return created;
            });

            if (isAck) {
                long latest = state.getRevision() == null ? 0L : state.getRevision();
                long acked = Math.max(ack.getLastAckedRevision() == null ? 0L : ack.getLastAckedRevision(),
                        Math.min(revision, latest));
                ack.setLastAckedRevision(acked);
            } else {
                Long reportedVehicle = firstLong(message, "vehicleId", null);
                Long reportedTrip = firstLong(message, "tripId", null);
                ack.setLastReportedRevision(revision);
                ack.setLastReportVehicleId(reportedVehicle);
                ack.setLastReportTripId(reportedTrip);
            }
            ack.setUpdatedAt(System.currentTimeMillis());
            ackRepository.save(ack);

            if (isStaleClientView(state, message, isAck, revision)) {
                forceRetry(driverId, deviceId, isAck ? "STALE_ACK" : "STALE_CLIENT_REPORT");
            }
        } catch (Exception e) {
            logger.warn("⚠️ Failed to handle driver-state client message on {}: {}", topic, e.getMessage());
        }
    }

    /** Hook for the existing users/+/location/update flow; no new client needed. */
    public void reportVehicleFromLocation(Long driverId, Long vehicleId) {
        if (driverId == null) return;
        DriverStateEntity state = stateRepository.findById(driverId).orElse(null);
        if (state == null) return;
        Long expectedVehicle = stateVehicleId(state);
        if (!Objects.equals(expectedVehicle, vehicleId)) {
            logger.warn("⚠️ Driver {} GPS reports vehicle={}, latest state vehicle={} — re-emitting",
                    driverId, vehicleId, expectedVehicle);
            forceRetry(driverId, "gps-location", "GPS_VEHICLE_MISMATCH");
        }
    }

    public void forceRetryAllDevices(Long driverId, String reason) {
        if (driverId == null) return;
        DriverStateEntity state = stateRepository.findById(driverId).orElse(null);
        if (state == null) return;
        long revision = state.getRevision() == null ? 0L : state.getRevision();
        for (DriverStateAckEntity ack : ackRepository.findByDriverId(driverId)) {
            ack.setLastAckedRevision(Math.min(revision - 1, ack.getLastAckedRevision() == null ? 0L : ack.getLastAckedRevision()));
            ack.setUpdatedAt(System.currentTimeMillis());
            ackRepository.save(ack);
        }
        publishNow(state, reason);
    }

    private void refresh(Long driverId, String reason,
                         Map<Long, VehicleDto> vehiclesByDriverArg,
                         List<JsonNode> activeTripsArg) {
        DriverStateEntity state = stateRepository.findById(driverId).orElseGet(this::newState);

        Map<Long, VehicleDto> vehiclesByDriver = vehiclesByDriverArg != null
                ? vehiclesByDriverArg
                : vehicleRegistryService.findVehiclesByDriver();
        List<JsonNode> activeTrips = activeTripsArg != null
                ? activeTripsArg
                : tripAssignmentReconciliationService.fetchActiveTrips();

        VehicleDto vehicle = vehiclesByDriver.get(driverId);
        JsonNode activeTrip = selectActiveTrip(driverId, vehicle, activeTrips);

        String sessionState = normalize(null, state.getSessionState(), "ACTIVE");
        boolean identityRevoked = sessionState.contains("REVOKED") || "LOGGED_OUT".equals(sessionState);
        // A revoked identity stays revoked until an explicit reactivation event.
        // Otherwise a demotion event consumed before the unassignment transaction
        // commits could read stale vehicle data and restore DRIVER.
        String role = identityRevoked
                ? normalize(null, state.getRole(), "CLIENT")
                : normalize(
                        vehicle != null && vehicle.getDriver() != null ? vehicle.getDriver().getRole() : null,
                        state.getRole(), "DRIVER");
        String userStatus = identityRevoked
                ? normalize(null, state.getUserStatus(), "INACTIVE")
                : normalize(
                        vehicle != null && vehicle.getDriver() != null ? vehicle.getDriver().getStatus() : null,
                        state.getUserStatus(), "ACTIVE");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("driverId", driverId);
        snapshot.put("role", role);
        snapshot.put("status", userStatus);
        snapshot.put("userStatus", userStatus);
        snapshot.put("sessionState", sessionState);
        snapshot.put("vehicle", vehicleSnapshot(vehicle));
        snapshot.put("activeTrip", tripSnapshot(activeTrip));

        String content;
        try {
            content = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            logger.error("❌ Failed to serialize driver-state snapshot for driver {}: {}", driverId, e.getMessage());
            return;
        }

        if (content.equals(state.getContentHash())) {
            return;
        }

        long revision = (state.getRevision() == null ? 0L : state.getRevision()) + 1L;
        long now = System.currentTimeMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "driver_state");
        payload.put("driverId", driverId.toString());
        payload.put("revision", revision);
        payload.put("reason", reason == null ? "STATE_CHANGED" : reason);
        payload.put("updatedAt", now);
        payload.put("snapshot", snapshot);

        try {
            state.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            logger.error("❌ Failed to serialize driver-state payload for driver {}: {}", driverId, e.getMessage());
            return;
        }

        state.setDriverId(driverId);
        state.setRevision(revision);
        state.setContentHash(content);
        state.setReason(reason);
        state.setRole(role);
        state.setUserStatus(userStatus);
        state.setSessionState(sessionState);
        state.setUpdatedAt(now);
        state.setNextPublishAt(now);
        state.setRetryAttempt(0);
        stateRepository.save(state);

        logger.info("📡 Driver-state revision {} for driver={} reason={} vehicle={} trip={}",
                revision, driverId, reason,
                vehicle == null ? null : vehicle.getId(),
                activeTrip == null ? null : activeTrip.path("id").asLong());
        publishNow(state, reason);
    }

    private Map<String, Object> vehicleSnapshot(VehicleDto vehicle) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (vehicle == null) {
            return result;
        }
        result.put("id", vehicle.getId());
        result.put("plate", vehicle.getLicensePlate());
        result.put("status", vehicle.getStatus());
        if (vehicle.getDriver() != null) {
            result.put("driverId", vehicle.getDriver().getId());
        }
        return result;
    }

    private Map<String, Object> tripSnapshot(JsonNode trip) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (trip == null) {
            return result;
        }
        result.put("id", trip.path("id").asLong());
        result.put("vehicleId", trip.path("vehicle_id").asInt());
        result.put("status", text(trip, "status"));
        return result;
    }

    /**
     * Choose the active trip that matches the driver's CURRENT vehicle. A trip
     * whose immutable snapshot points at an old car is not authoritative.
     */
    private JsonNode selectActiveTrip(Long driverId, VehicleDto currentVehicle, List<JsonNode> trips) {
        if (trips == null || currentVehicle == null || currentVehicle.getId() == null) return null;
        Long currentVehicleId = currentVehicle.getId();
        List<JsonNode> candidates = new ArrayList<>();
        for (JsonNode trip : trips) {
            long tripDriver = trip.path("vehicle").path("driver").path("id").asLong();
            long tripVehicle = trip.path("vehicle_id").asLong();
            if (tripDriver != driverId || tripVehicle != currentVehicleId) continue;
            candidates.add(trip);
        }
        candidates.sort(Comparator
                .comparingInt((JsonNode t) -> "IN_PROGRESS".equalsIgnoreCase(text(t, "status")) ? 0 : 1)
                .thenComparingLong(t -> {
                    JsonNode departure = t.get("departure_time");
                    return departure == null || departure.isNull() ? Long.MAX_VALUE : departure.asLong();
                }));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void publishIfDue(DriverStateEntity state, long now) {
        if (state == null || state.getRevision() == null) return;
        long revision = state.getRevision();
        Long lastPublished = state.getLastPublishedRevision();
        List<DriverStateAckEntity> devices = ackRepository.findByDriverId(state.getDriverId());
        // No known device yet: the retained message is enough. A device that
        // subscribes later creates an ACK row and joins the retry loop then.
        if (devices.isEmpty() && lastPublished != null && lastPublished >= revision) {
            return;
        }
        long unacked = devices.stream()
                .filter(ack -> ack.getLastAckedRevision() == null || ack.getLastAckedRevision() < revision)
                .count();
        if ((lastPublished != null && lastPublished >= revision) && unacked == 0) {
            return;
        }
        Long nextPublish = state.getNextPublishAt();
        if (nextPublish != null && now < nextPublish) {
            return;
        }
        publishNow(state, state.getReason());
    }

    private void publishNow(DriverStateEntity state, String reason) {
        if (state == null || state.getPayload() == null) return;
        try {
            mqttService.publishDriverState(state.getDriverId(), state.getPayload());
            long now = System.currentTimeMillis();
            int attempt = state.getRetryAttempt() == null ? 0 : state.getRetryAttempt();
            state.setLastPublishedRevision(state.getRevision());
            state.setRetryAttempt(attempt + 1);
            state.setNextPublishAt(now + backoffMs(attempt));
            state.setReason(reason == null ? state.getReason() : reason);
            stateRepository.save(state);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to publish driver-state for driver {}: {}", state.getDriverId(), e.getMessage());
        }
    }

    private long backoffMs(int attempt) {
        int index = Math.max(0, Math.min(attempt, RETRY_BACKOFF_MS.length - 1));
        return RETRY_BACKOFF_MS[index];
    }

    private void forceRetry(Long driverId, String deviceId, String reason) {
        DriverStateEntity state = stateRepository.findById(driverId).orElse(null);
        if (state == null) return;
        long revision = state.getRevision() == null ? 0L : state.getRevision();
        String key = driverId + ":" + deviceId;
        DriverStateAckEntity ack = ackRepository.findById(key).orElseGet(() -> {
            DriverStateAckEntity created = new DriverStateAckEntity();
            created.setId(key);
            created.setDriverId(driverId);
            created.setDeviceId(deviceId);
            created.setLastAckedRevision(0L);
            created.setLastReportedRevision(0L);
            return created;
        });
        ack.setLastAckedRevision(Math.min(revision - 1,
                ack.getLastAckedRevision() == null ? 0L : ack.getLastAckedRevision()));
        ack.setUpdatedAt(System.currentTimeMillis());
        ackRepository.save(ack);
        state.setNextPublishAt(System.currentTimeMillis());
        state.setRetryAttempt(0);
        stateRepository.save(state);
        logger.info("🔁 Re-emitting driver-state revision {} to device={} reason={}", revision, deviceId, reason);
        publishNow(state, reason);
    }

    private boolean isStaleClientView(DriverStateEntity state, JsonNode message, boolean isAck, long reportedRevision) {
        long revision = state.getRevision() == null ? 0L : state.getRevision();
        if (reportedRevision < revision) return true;

        Long reportedVehicle = firstLong(message, "vehicleId", null);
        Long reportedTrip = firstLong(message, "tripId", null);
        if (!isAck) {
            if (!Objects.equals(reportedVehicle, stateVehicleId(state))) return true;
            if (!Objects.equals(reportedTrip, stateTripId(state))) return true;
            String reportedRole = text(message, "role");
            String reportedStatus = text(message, "userStatus");
            if (reportedRole != null && !reportedRole.equalsIgnoreCase(state.getRole())) return true;
            if (reportedStatus != null && !reportedStatus.equalsIgnoreCase(state.getUserStatus())) return true;
        }
        return false;
    }

    private Long stateVehicleId(DriverStateEntity state) {
        return readLongFromPayload(state, "vehicle", "id");
    }

    private Long stateTripId(DriverStateEntity state) {
        return readLongFromPayload(state, "activeTrip", "id");
    }

    private Long readLongFromPayload(DriverStateEntity state, String parent, String child) {
        if (state == null || state.getPayload() == null) return null;
        try {
            JsonNode snapshot = objectMapper.readTree(state.getPayload()).path("snapshot").path(parent);
            if (snapshot == null || snapshot.isNull() || snapshot.path(child).isNull()) return null;
            long value = snapshot.path(child).asLong();
            return value > 0 ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private DriverStateEntity newState() {
        DriverStateEntity state = new DriverStateEntity();
        state.setRevision(0L);
        state.setLastPublishedRevision(0L);
        state.setRetryAttempt(0);
        return state;
    }

    private Long extractDriverIdFromTopic(String topic) {
        if (topic == null || !topic.startsWith("drivers/")) return null;
        String[] parts = topic.split("/");
        if (parts.length < 2) return null;
        try {
            return Long.valueOf(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long firstLong(JsonNode json, String key, Long fallback) {
        if (json == null) return fallback;
        JsonNode value = json.get(key);
        if (value == null || value.isNull()) return fallback;
        if (value.isNumber()) return value.asLong();
        if (value.isTextual()) {
            try { return Long.valueOf(value.asText()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private String text(JsonNode json, String key) {
        if (json == null) return null;
        JsonNode value = json.get(key);
        if (value == null || value.isNull()) return null;
        String result = value.asText();
        return result == null || result.isBlank() ? null : result;
    }

    private String normalize(String value, String fallback, String defaultValue) {
        if (value != null && !value.isBlank()) return value.trim().toUpperCase();
        if (fallback != null && !fallback.isBlank()) return fallback;
        return defaultValue;
    }
}
