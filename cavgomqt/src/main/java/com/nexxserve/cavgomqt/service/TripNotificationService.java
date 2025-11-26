package com.nexxserve.cavgomqt.service;

import com.nexxserve.cavgomqt.dto.Location;
import com.nexxserve.cavgomqt.dto.Route;
import com.nexxserve.cavgomqt.dto.Trip;
import com.nexxserve.cavgomqt.dto.TripStatus;
import com.nexxserve.cavgomqt.entity.TripNotificationEntity;
import com.nexxserve.cavgomqt.repository.TripNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for handling trip completion notifications via Firebase Cloud Messaging.
 * Sends notifications when trips are about to complete (< 1km remaining) and when completed.
 */
@Service
public class TripNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(TripNotificationService.class);
    private static final String TRIPS_UPDATES_TOPIC = "tripsupdates";
    private static final long ONE_KM_IN_METERS = 1000L;

    /**
     * Get the topic name based on company ID.
     * If company_id is provided, returns "tripsupdates_{companyId}", otherwise returns "tripsupdates"
     */
    private String getTopicName(Integer companyId) {
        if (companyId != null) {
            return TRIPS_UPDATES_TOPIC + "_" + companyId;
        }
        return TRIPS_UPDATES_TOPIC;
    }

    @Autowired
    private FirebaseService firebaseService;

    @Autowired
    private TripNotificationRepository tripNotificationRepository;

    /**
     * Check if trip is about to complete and send notification if conditions are met.
     * Only sends notification once per trip (tracked in database).
     * 
     * @param trip The trip to check
     */
    @Transactional
    public void checkAndSendAboutToCompleteNotification(Trip trip) {
        try {
            // Validate trip data
            if (trip == null || trip.getId() == null) {
                logger.warn("⚠️ Cannot process notification: trip or trip ID is null");
                return;
            }

            // Check if status is IN_PROGRESS
            if (trip.getStatus() != TripStatus.IN_PROGRESS) {
                logger.info("⏭️ Trip {} status is {} (required: IN_PROGRESS), skipping 'about to complete' notification", 
                           trip.getId(), trip.getStatus());
                return;
            }

            // Check if remaining distance is less than 1km
            Long remainingDistance = trip.getRemainingDistanceToDestination();
            if (remainingDistance == null) {
                logger.info("⏭️ Trip {} has no remaining distance data, skipping 'about to complete' notification", 
                           trip.getId());
                return;
            }
            if (remainingDistance >= ONE_KM_IN_METERS) {
                logger.info("⏭️ Trip {} remaining distance is {}m (>= 1km threshold), skipping 'about to complete' notification", 
                           trip.getId(), remainingDistance);
                return;
            }

            // Check if notification was already sent (with INFO level for visibility)
            if (tripNotificationRepository.existsByTripId(trip.getId())) {
                logger.info("⏭️ Trip {} 'about to complete' notification already sent (preventing duplicate), skipping", trip.getId());
                return;
            }
            
            logger.info("✅ Trip {} meets conditions for 'about to complete' notification: status={}, distance={}m (< 1km)", 
                       trip.getId(), trip.getStatus(), remainingDistance);

            // Get vehicle information
            String licensePlate = getLicensePlate(trip);
            Integer vehicleId = getVehicleId(trip);
            Integer companyId = getCompanyId(trip);

            if (licensePlate == null || vehicleId == null) {
                logger.warn("⚠️ Cannot send notification: missing vehicle information for trip {}", trip.getId());
                return;
            }

            // Build notification message
            String message = buildAboutToCompleteMessage(trip);
            if (message == null) {
                logger.warn("⚠️ Cannot build notification message for trip {}", trip.getId());
                return;
            }

            // Build payload
            Map<String, String> payload = new HashMap<>();
            payload.put("trip_id", String.valueOf(trip.getId()));
            payload.put("car_id", String.valueOf(vehicleId));
            payload.put("plate", licensePlate);
            payload.put("message", message);
            payload.put("type", "about_to_complete");

            // Get topic name based on company ID
            String topicName = getTopicName(companyId);
            logger.info("📤 Publishing to topic: {} (company_id: {})", topicName, companyId);

            // Send notification with collapse key
            String collapseKey = "trip_about_to_complete_" + trip.getId();
            String messageId = firebaseService.sendMessageToTopicWithCollapseKey(
                topicName, collapseKey, payload);

            if (messageId != null) {
                // Save to database to prevent duplicate notifications
                // Use try-catch to handle potential race conditions (if another thread already saved)
                try {
                    TripNotificationEntity notification = new TripNotificationEntity();
                    notification.setTripId(trip.getId());
                    notification.setVehicleId(vehicleId);
                    notification.setLicensePlate(licensePlate);
                    notification.setSentAt(LocalDateTime.now());
                    tripNotificationRepository.save(notification);
                    logger.info("✅ Sent 'about to complete' notification for trip {}: {} (saved to DB)", trip.getId(), messageId);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // Another thread/process already saved this notification (race condition handled)
                    logger.info("ℹ️ Trip {} notification already exists in DB (race condition handled gracefully)", trip.getId());
                    logger.info("✅ Sent 'about to complete' notification for trip {}: {}", trip.getId(), messageId);
                }
            } else {
                logger.error("❌ Failed to send 'about to complete' notification for trip {}", trip.getId());
            }

        } catch (Exception e) {
            logger.error("❌ Error processing 'about to complete' notification for trip {}: {}", 
                        trip != null ? trip.getId() : "null", e.getMessage(), e);
        }
    }

    /**
     * Send completion notification and delete the "about to complete" record from database.
     * 
     * @param trip The completed trip
     */
    @Transactional
    public void sendCompletionNotification(Trip trip) {
        try {
            // Validate trip data
            if (trip == null || trip.getId() == null) {
                logger.warn("⚠️ Cannot process completion notification: trip or trip ID is null");
                return;
            }

            // Check if status is COMPLETED
            if (trip.getStatus() != TripStatus.COMPLETED) {
                logger.debug("Trip {} status is {}, skipping completion notification", 
                           trip.getId(), trip.getStatus());
                return;
            }

            // Get vehicle information
            String licensePlate = getLicensePlate(trip);
            Integer vehicleId = getVehicleId(trip);
            Integer companyId = getCompanyId(trip);

            if (licensePlate == null || vehicleId == null) {
                logger.warn("⚠️ Cannot send completion notification: missing vehicle information for trip {}", 
                           trip.getId());
                return;
            }

            // Build notification message
            String message = buildCompletionMessage(trip);
            if (message == null) {
                logger.warn("⚠️ Cannot build completion message for trip {}", trip.getId());
                return;
            }

            // Build payload
            Map<String, String> payload = new HashMap<>();
            payload.put("trip_id", String.valueOf(trip.getId()));
            payload.put("car_id", String.valueOf(vehicleId));
            payload.put("plate", licensePlate);
            payload.put("message", message);
            payload.put("type", "completed");

            // Get topic name based on company ID
            String topicName = getTopicName(companyId);
            logger.info("📤 Publishing to topic: {} (company_id: {})", topicName, companyId);

            // Send notification with collapse key
            String collapseKey = "trip_completed_" + trip.getId();
            String messageId = firebaseService.sendMessageToTopicWithCollapseKey(
                topicName, collapseKey, payload);

            if (messageId != null) {
                // Delete "about to complete" record if it exists
                tripNotificationRepository.deleteByTripId(trip.getId());
                logger.info("✅ Sent completion notification for trip {} and cleaned up database: {}", 
                           trip.getId(), messageId);
            } else {
                logger.error("❌ Failed to send completion notification for trip {}", trip.getId());
            }

        } catch (Exception e) {
            logger.error("❌ Error processing completion notification for trip {}: {}", 
                        trip != null ? trip.getId() : "null", e.getMessage(), e);
        }
    }

    /**
     * Build the "about to complete" notification message.
     */
    private String buildAboutToCompleteMessage(Trip trip) {
        Route route = trip.getRoute();
        if (route == null) {
            return null;
        }

        Location origin = route.getOrigin();
        Location destination = route.getDestination();

        if (origin == null || destination == null) {
            return null;
        }

        String originName = origin.getCustomName() != null && !origin.getCustomName().isEmpty() 
            ? origin.getCustomName() 
            : (origin.getGooglePlaceName() != null ? origin.getGooglePlaceName() : "Unknown");
        
        String destinationName = destination.getCustomName() != null && !destination.getCustomName().isEmpty()
            ? destination.getCustomName()
            : (destination.getGooglePlaceName() != null ? destination.getGooglePlaceName() : "Unknown");

        return String.format("trip %s -> %s is about to be completed (less than 1km remaining)", 
                           originName, destinationName);
    }

    /**
     * Build the completion notification message.
     */
    private String buildCompletionMessage(Trip trip) {
        Route route = trip.getRoute();
        if (route == null) {
            return null;
        }

        Location origin = route.getOrigin();
        Location destination = route.getDestination();

        if (origin == null || destination == null) {
            return null;
        }

        String originName = origin.getCustomName() != null && !origin.getCustomName().isEmpty() 
            ? origin.getCustomName() 
            : (origin.getGooglePlaceName() != null ? origin.getGooglePlaceName() : "Unknown");
        
        String destinationName = destination.getCustomName() != null && !destination.getCustomName().isEmpty()
            ? destination.getCustomName()
            : (destination.getGooglePlaceName() != null ? destination.getGooglePlaceName() : "Unknown");

        return String.format("trip %s -> %s has been completed", originName, destinationName);
    }

    /**
     * Get license plate from trip, with fallback options.
     */
    private String getLicensePlate(Trip trip) {
        if (trip.getVehicle() != null && trip.getVehicle().getLicensePlate() != null) {
            return trip.getVehicle().getLicensePlate();
        }
        return null;
    }

    /**
     * Get vehicle ID from trip, with fallback options.
     */
    private Integer getVehicleId(Trip trip) {
        if (trip.getVehicle() != null && trip.getVehicle().getId() != null) {
            return trip.getVehicle().getId();
        }
        if (trip.getVehicleId() != null) {
            return trip.getVehicleId();
        }
        return null;
    }

    /**
     * Get company ID from trip vehicle data.
     */
    private Integer getCompanyId(Trip trip) {
        if (trip.getVehicle() != null && trip.getVehicle().getCompanyId() != null) {
            return trip.getVehicle().getCompanyId();
        }
        return null;
    }
}



