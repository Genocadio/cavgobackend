package com.gocavgo.Navigation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
public class HeartbeatController {

    @PostMapping("/HEARTBEAT")
    public ResponseEntity<Map<String, Object>> heartbeatPost() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "Navigation",
                "timestamp", Instant.now().toString()
        ));
    }

    @PostMapping("/HEARTBEAT/")
    public ResponseEntity<Map<String, Object>> heartbeatPostWithTrailingSlash() {
        return heartbeatPost();
    }

    @GetMapping("/HEARTBEAT")
    public ResponseEntity<Map<String, Object>> heartbeatGet() {
        return heartbeatPost();
    }

    @GetMapping("/HEARTBEAT/")
    public ResponseEntity<Map<String, Object>> heartbeatGetWithTrailingSlash() {
        return heartbeatPost();
    }
}
