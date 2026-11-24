package com.nexxserve.cavgomqt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.service.VehicleRegistryService.AssignmentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps active trips aligned with the driver's CURRENT vehicle assignment.
 *
 * cavgotrips copies the vehicle + driver into an immutable JSONB snapshot when a
 * trip is created. Reassigning a driver in cavgomain does not update that
 * snapshot, so the old trip keeps showing up for the old driver/vehicle and
 * Naviga keeps a trip bound to the old car. This service reconciles against
 * cavgomain's live assignments and cancels stale active trips, which triggers
 * the existing cancellation cascade (cavgotrips -> RabbitMQ -> Naviga -> MQTT).
 */
@Service
public class TripAssignmentReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(TripAssignmentReconciliationService.class);
    private static final String[] ACTIVE_STATUSES = {"SCHEDULED", "IN_PROGRESS"};

    @Value("${trip.service.url:http://cavgotrips:6080}")
    private String tripServiceUrl;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleRegistryService vehicleRegistryService;

    /**
     * Called before a newly created trip is sent to Naviga. Returns true when the
     * trip was stale and has been cancelled, so the caller must not start it.
     */
    public boolean cancelIfStale(Trip trip, String source) {
        if (trip == null || trip.getId() == null || trip.getVehicleId() == null
                || trip.getVehicle() == null || trip.getVehicle().getDriver() == null) {
            return false;
        }

        Integer snapshotDriverId = trip.getVehicle().getDriver().getId();
        if (snapshotDriverId == null) {
            return false;
        }

        AssignmentSnapshot assignments = vehicleRegistryService.getCurrentAssignments();
        if (!isStale(snapshotDriverId, trip.getVehicleId(), assignments)) {
            return false;
        }

        logger.warn("⚠️ Cancelling stale trip {}: snapshot driver={} vehicle={}, current assignments={} (source={})",
                trip.getId(), snapshotDriverId, trip.getVehicleId(), describe(assignments), source);
        cancelTrip(Long.valueOf(trip.getId()));
        return true;
    }

    /**
     * Clean up trips that became stale before this service existed or while it
     * was down. The cancel is idempotent: cavgotrips only publishes a cancellation
     * event for trips that are still SCHEDULED or IN_PROGRESS.
     */
    @Scheduled(initialDelayString = "${trip.assignment.reconcile.initial-delay-ms:30000}",
            fixedDelayString = "${trip.assignment.reconcile.interval-ms:60000}")
    public void reconcileActiveTrips() {
        try {
            AssignmentSnapshot assignments = vehicleRegistryService.getCurrentAssignments();
            if (assignments.driverToVehicle.isEmpty() && assignments.vehicleToDriver.isEmpty()) {
                logger.debug("Skipping trip assignment reconciliation: no current vehicle assignments available");
                return;
            }

            Set<Long> cancelled = new HashSet<>();
            for (String status : ACTIVE_STATUSES) {
                JsonNode trips = fetchTripsByStatus(status);
                if (trips == null || !trips.isArray()) {
                    continue;
                }
                for (JsonNode trip : trips) {
                    long tripId = trip.path("id").asLong();
                    int vehicleId = trip.path("vehicle_id").asInt();
                    JsonNode driverNode = trip.path("vehicle").path("driver").path("id");
                    if (tripId <= 0 || vehicleId <= 0 || !driverNode.canConvertToInt()) {
                        continue;
                    }
                    if (cancelled.contains(tripId)) {
                        continue;
                    }
                    int snapshotDriverId = driverNode.asInt();
                    if (isStale(snapshotDriverId, vehicleId, assignments)) {
                        logger.warn("⚠️ Reconciling stale active trip {}: snapshot driver={} vehicle={}, current assignments={}",
                                tripId, snapshotDriverId, vehicleId, describe(assignments));
                        cancelTrip(tripId);
                        cancelled.add(tripId);
                    }
                }
            }
            if (!cancelled.isEmpty()) {
                logger.info("✅ Cancelled {} stale active trip(s)", cancelled.size());
            }
        } catch (Exception e) {
            logger.error("❌ Failed to reconcile active trips with current vehicle assignments: {}", e.getMessage(), e);
        }
    }

    private JsonNode fetchTripsByStatus(String status) {
        String url = tripServiceUrl + "/trips?status=" + status;
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                logger.warn("⚠️ Failed to fetch {} trips from trip service: status={}", status, response.getStatusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("trips");
        } catch (Exception e) {
            logger.warn("⚠️ Failed to fetch {} trips from trip service: {}", status, e.getMessage());
            return null;
        }
    }

    private void cancelTrip(Long tripId) {
        try {
            String url = tripServiceUrl + "/trips/" + tripId;
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.DELETE, null, String.class);
            logger.info("🗑️ Cancelled stale trip {} via trip service (status={})", tripId, response.getStatusCode());
        } catch (Exception e) {
            // The periodic reconciler will retry. Do not fail trip creation flow.
            logger.error("❌ Failed to cancel stale trip {}: {}", tripId, e.getMessage());
        }
    }

    /**
     * A trip is stale when the driver in its snapshot no longer drives that
     * vehicle, or when that driver is now assigned to a different vehicle.
     */
    private boolean isStale(int snapshotDriverId, int tripVehicleId, AssignmentSnapshot assignments) {
        Integer currentVehicleForDriver = assignments.driverToVehicle.get(snapshotDriverId);
        Integer currentDriverForVehicle = assignments.vehicleToDriver.get(tripVehicleId);

        if (currentVehicleForDriver != null && currentVehicleForDriver != tripVehicleId) {
            return true;
        }
        // Only treat a vehicle/driver mismatch as stale when the vehicle has a
        // definitive current driver. A temporarily unassigned vehicle is not a
        // reason to cancel an otherwise valid trip.
        return currentDriverForVehicle != null && currentDriverForVehicle != snapshotDriverId;
    }

    private String describe(AssignmentSnapshot assignments) {
        return "driverToVehicle=" + assignments.driverToVehicle + ", vehicleToDriver=" + assignments.vehicleToDriver;
    }
}
