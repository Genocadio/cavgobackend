package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.proto.LocationProto.LocationBatch;
import com.nexxserve.cavgomqt.proto.LocationProto.LocationPoint;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * MQTT Location Listener Service
 * 
 * This service consumes vehicle location batches published to the topic:
 * vehicles/{carId}/location/batch
 * 
 * The payload is a binary Protobuf message containing:
 * - vehicleId: unique vehicle identifier
 * - plate: vehicle license plate
 * - points: array of location points with
 * lat/lng/speed/bearing/accuracy/timestamp
 * 
 * Location reporting logic uses adaptive batching:
 * - Real-time: batch every 10 seconds when moving (>1 m/s)
 * - Heartbeat: single point every 5 minutes when stationary
 * - Durable replay: may contain many points after offline periods
 */
@Service
public class MqttLocationListenerService {

    private static final Logger logger = LoggerFactory.getLogger(MqttLocationListenerService.class);

    @Autowired
    private NavigaService navigaService;

    /**
     * Process incoming location batch message from MQTT
     * 
     * @param topic   The MQTT topic the message was received on
     * @param payload Binary Protobuf payload
     */
    public void processLocationBatch(String topic, byte[] payload) {
        logger.info("═══════════════════════════════════════════════════════════════");
        logger.info("📍 === RECEIVED LOCATION BATCH FROM MQTT ===");
        logger.info("  - Timestamp: {}", System.currentTimeMillis());
        logger.info("  - Topic: {}", topic);
        logger.info("  - Payload size: {} bytes", payload != null ? payload.length : 0);

        if (payload == null || payload.length == 0) {
            logger.warn("  ⚠️  Empty payload received, skipping");
            logger.info("═══════════════════════════════════════════════════════════════");
            return;
        }

        try {
            // Decode Protobuf message
            LocationBatch batch = LocationBatch.parseFrom(payload);

            logger.info("✅ Successfully decoded LocationBatch:");
            logger.info("  - Vehicle ID: {}", batch.getVehicleId());
            logger.info("  - License Plate: {}", batch.getPlate());
            logger.info("  - Number of Points: {}", batch.getPointsCount());

            // Check for empty batch (heartbeat only)
            if (batch.getPointsCount() == 0) {
                logger.info("  ℹ️  Empty points list - this is a connection heartbeat only");
            } else {
                logger.info("📊 Location Points:");

                // Collect all GPS updates for batch sending to Naviga
                java.util.List<com.nexxserve.cavgomqt.dto.naviga.NavigaGpsUpdateRequest> gpsUpdates = 
                    new java.util.ArrayList<>();

                // Process and log each location point
                for (int i = 0; i < batch.getPointsCount(); i++) {
                    LocationPoint point = batch.getPoints(i);

                    logger.info("  Point #{}: ", (i + 1));
                    logger.info("    - Latitude: {}", point.getLat());
                    logger.info("    - Longitude: {}", point.getLng());
                    logger.info("    - Speed: {} m/s", point.getSpeed());

                    // Optional fields
                    Double bearing = null;
                    if (point.hasBearing()) {
                        bearing = (double) point.getBearing();
                        logger.info("    - Bearing: {}°", point.getBearing());
                    }
                    
                    Double accuracy = null;
                    if (point.hasAccuracy()) {
                        accuracy = (double) point.getAccuracy();
                        logger.info("    - Accuracy: {} meters", point.getAccuracy());
                    }
                    
                    logger.info("    - Timestamp: {} (Unix ms)", point.getTimestamp());

                    // Convert timestamp to human-readable format
                    java.time.Instant instant = java.time.Instant.ofEpochMilli(point.getTimestamp());
                    logger.info("    - Time: {}", instant.toString());

                    // Build GPS update request
                    com.nexxserve.cavgomqt.dto.naviga.NavigaGpsUpdateRequest gpsUpdate = 
                        new com.nexxserve.cavgomqt.dto.naviga.NavigaGpsUpdateRequest();
                    gpsUpdate.setLatitude(point.getLat());
                    gpsUpdate.setLongitude(point.getLng());
                    gpsUpdate.setSpeed((double) point.getSpeed());
                    
                    if (bearing != null) {
                        gpsUpdate.setHeading(bearing);
                    }
                    if (accuracy != null) {
                        gpsUpdate.setAccuracy(accuracy);
                    }
                    
                    // Convert Unix timestamp to ISO 8601 format
                    gpsUpdate.setTimestamp(
                        java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant)
                    );
                    
                    gpsUpdates.add(gpsUpdate);
                }

                // Send all GPS updates in a single batch to Naviga API
                if (!gpsUpdates.isEmpty()) {
                    try {
                        // NavigaService will validate registry presence and log outcomes
                        navigaService.updateGpsBatch(batch.getVehicleId(), gpsUpdates);
                    } catch (Exception e) {
                        logger.warn("⚠️ Failed to send GPS batch to Naviga API: {}", e.getMessage());
                    }
                }
            }

            // TODO: Forward to RabbitMQ as needed (if required for other services)

        } catch (InvalidProtocolBufferException e) {
            logger.error("❌ FAILED to decode Protobuf LocationBatch:");
            logger.error("  - Topic: {}", topic);
            logger.error("  - Payload size: {} bytes", payload.length);
            logger.error("  - Error: {}", e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            logger.error("❌ Unexpected error processing location batch:");
            logger.error("  - Topic: {}", topic);
            logger.error("  - Error: {}", e.getMessage());
            e.printStackTrace();
        }

        logger.info("✅ Finished processing location batch");
        logger.info("═══════════════════════════════════════════════════════════════");
    }
}
