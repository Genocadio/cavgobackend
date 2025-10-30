package com.nexxserve.cavgomain.scheduled;

import com.nexxserve.cavgomain.repository.VehicleLocationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class VehicleMaintenanceScheduler {

    private final VehicleLocationRepository locationRepository;

    /**
     * Delete location records older than 48 hours
     * Runs every hour
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at minute 0
    @Transactional
    public void cleanupOldLocations() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(48);
            int deletedCount = locationRepository.deleteByRecordedAtBefore(cutoffTime);
            
            if (deletedCount > 0) {
                log.info("Cleaned up {} old location records older than {}", deletedCount, cutoffTime);
            }
        } catch (Exception e) {
            log.error("Error cleaning up old location records", e);
        }
    }

    /**
     * Check and log vehicles that are offline (no update in 30 minutes)
     * This is informational - the isOnline() method on Vehicle entity handles the actual check
     * Runs every 5 minutes
     */
    @Scheduled(cron = "0 */5 * * * *") // Every 5 minutes
    public void checkOfflineVehicles() {
        try {
            // This is primarily for monitoring/logging purposes
            // The actual online/offline status is determined by the Vehicle.isOnline() method
            log.debug("Running offline vehicle check (informational only)");
            // Additional monitoring logic can be added here if needed
        } catch (Exception e) {
            log.error("Error checking offline vehicles", e);
        }
    }
}

