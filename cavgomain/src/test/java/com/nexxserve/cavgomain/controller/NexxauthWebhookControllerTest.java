package com.nexxserve.cavgomain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexxserve.cavgomain.messaging.EventMessagePublisher;
import com.nexxserve.cavgomain.security.NexxauthWebhookVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NexxauthWebhookControllerTest {

    @Mock
    private NexxauthWebhookVerifier verifier;

    @Mock
    private EventMessagePublisher publisher;

    private NexxauthWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new NexxauthWebhookController(verifier, publisher);
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void translatesSignInIntoDriverSessionStarted() throws Exception {
        when(verifier.verify("Bearer valid")).thenReturn(new NexxauthWebhookVerifier.WebhookClaims(
                5L, 5L, "USER_SIGNED_IN", "LOGIN", "jti-1"));

        ResponseEntity<?> response = controller.handle("Bearer valid", objectMapper.readTree(
                "{\"event\":\"USER_SIGNED_IN\",\"data\":{\"userId\":5,\"orgId\":5,\"reason\":\"LOGIN\"}}"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishDriverEvent(eq("DRIVER_SESSION_STARTED"), data.capture());
        assertEquals(Map.of("driverId", 5L, "orgId", 5L, "reason", "LOGIN"), data.getValue());
    }

    @Test
    void rejectsMismatchedBodyIdentity() throws Exception {
        when(verifier.verify("Bearer valid")).thenReturn(new NexxauthWebhookVerifier.WebhookClaims(
                5L, 5L, "USER_SESSION_REVOKED", "LOGOUT", "jti-2"));

        ResponseEntity<?> response = controller.handle("Bearer valid", objectMapper.readTree(
                "{\"event\":\"USER_SESSION_REVOKED\",\"data\":{\"userId\":6}}"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
