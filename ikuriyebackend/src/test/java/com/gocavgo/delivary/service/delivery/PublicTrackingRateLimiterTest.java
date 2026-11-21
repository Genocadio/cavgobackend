package com.gocavgo.delivary.service.delivery;

import com.gocavgo.delivary.exception.BusinessValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicTrackingRateLimiterTest {

    @Test
    void allowsRequestsUpToWindowLimit() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter();

        for (int i = 0; i < PublicTrackingRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            assertDoesNotThrow(() -> limiter.checkRateLimit("203.0.113.10"));
        }
    }

    @Test
    void blocksRequestsAfterWindowLimit() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter();
        String clientKey = "203.0.113.11";

        for (int i = 0; i < PublicTrackingRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.checkRateLimit(clientKey);
        }

        assertThrows(BusinessValidationException.class, () -> limiter.checkRateLimit(clientKey));
    }

    @Test
    void limitsClientsIndependently() {
        PublicTrackingRateLimiter limiter = new PublicTrackingRateLimiter();

        for (int i = 0; i < PublicTrackingRateLimiter.MAX_REQUESTS_PER_WINDOW; i++) {
            limiter.checkRateLimit("203.0.113.12");
        }

        assertDoesNotThrow(() -> limiter.checkRateLimit("203.0.113.13"));
    }
}
