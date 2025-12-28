package com.gocavgo.Navigation.api;

import com.gocavgo.Navigation.service.ResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/reset")
@RequiredArgsConstructor
@Slf4j
public class ResetController {
    
    private final ResetService resetService;
    
    /**
     * Reset the system by deleting all inactive trips and active trips older than 24 hours.
     * Also deletes associated navigation state and snapshots.
     * 
     * @return Response with counts of deleted items
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> resetSystem() {
        try {
            log.info("Reset endpoint called");
            ResetService.ResetResult result = resetService.resetSystem();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("deletedTrips", result.getDeletedTrips());
            response.put("deletedSnapshots", result.getDeletedSnapshots());
            response.put("deletedRedisStates", result.getDeletedRedisStates());
            response.put("message", "System reset completed successfully");
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during system reset", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}

