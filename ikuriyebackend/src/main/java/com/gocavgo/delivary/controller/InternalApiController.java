package com.gocavgo.delivary.controller;

import com.gocavgo.delivary.service.user.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints for cavgomain. No auth — internal only,
 * reachable only within the private network (permitted in {@link SecurityConfig}
 * under {@code /internal/api/**}).
 */
@RestController
@RequestMapping("/internal/api")
@RequiredArgsConstructor
public class InternalApiController {

    private static final Logger log = LoggerFactory.getLogger(InternalApiController.class);
    private final UserService userService;

    /**
     * Receives a worker's office location id pushed by cavgomain whenever a
     * WORKER's office is assigned or changed there. Stores it as the worker's
     * FK-free {@code company_id} on {@code worker_profiles} (cleared when the
     * id is {@code null}).
     */
    @PostMapping("/users/office-sync")
    public ResponseEntity<Void> syncWorkerOffice(@RequestBody WorkerOfficeSyncRequest request) {
        if (request.userId() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            userService.applyOfficeLocation(request.userId(), request.officeLocationId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("office-sync failed for userId={}: {}", request.userId(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Body pushed by cavgomain: the Nexxauth user id + the office location id. */
    public record WorkerOfficeSyncRequest(Long userId, String officeLocationId) {
    }
}