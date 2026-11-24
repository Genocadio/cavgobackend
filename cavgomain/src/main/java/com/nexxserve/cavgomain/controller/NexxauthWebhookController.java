package com.nexxserve.cavgomain.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexxserve.cavgomain.messaging.EventMessagePublisher;
import com.nexxserve.cavgomain.security.JwtAuthenticationException;
import com.nexxserve.cavgomain.security.NexxauthWebhookVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives signed Nexxauth lifecycle events and translates them into CavGo
 * driver-state events. No Nexxauth credentials are exposed here: the caller
 * proves ownership of the CavGo organisation signing key with a short-lived JWT.
 */
@RestController
@RequestMapping("/main/webhooks/nexxauth")
public class NexxauthWebhookController {

    private static final Logger log = LoggerFactory.getLogger(NexxauthWebhookController.class);

    private final NexxauthWebhookVerifier verifier;
    private final EventMessagePublisher eventMessagePublisher;

    public NexxauthWebhookController(NexxauthWebhookVerifier verifier,
                                     EventMessagePublisher eventMessagePublisher) {
        this.verifier = verifier;
        this.eventMessagePublisher = eventMessagePublisher;
    }

    @PostMapping("/events")
    public ResponseEntity<?> handle(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode body
    ) {
        final NexxauthWebhookVerifier.WebhookClaims claims;
        try {
            claims = verifier.verify(authorization);
        } catch (JwtAuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid webhook token"));
        }

        String bodyEvent = text(body, "event");
        if (!claims.event().equals(bodyEvent)) {
            return ResponseEntity.badRequest().body(Map.of("error", "event mismatch"));
        }
        JsonNode data = body.get("data");
        Long bodyUserId = data == null ? null : longValue(data.get("userId"));
        if (bodyUserId == null || bodyUserId <= 0 || !claims.userId().equals(bodyUserId)) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId mismatch"));
        }

        String driverEvent = switch (claims.event()) {
            case "USER_SIGNED_IN" -> "DRIVER_SESSION_STARTED";
            case "USER_SESSION_REVOKED" -> "DRIVER_SESSION_REVOKED";
            default -> null;
        };
        if (driverEvent == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported event"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("driverId", claims.userId());
        payload.put("orgId", claims.orgId());
        payload.put("reason", claims.reason());
        eventMessagePublisher.publishDriverEvent(driverEvent, payload);

        log.info("Nexxauth webhook accepted: event={} driver={} reason={}",
                claims.event(), claims.userId(), claims.reason());
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long longValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.longValue();
        String value = node.asText();
        if (value == null || !value.matches("\\d+")) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
