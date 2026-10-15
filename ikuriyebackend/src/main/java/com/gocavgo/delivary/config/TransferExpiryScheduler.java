package com.gocavgo.delivary.config;

import com.gocavgo.delivary.service.transfer.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodically expires unconfirmed package transfers (PENDING or REQUESTED)
 * that have exceeded their 2-hour Time-To-Live (TTL).
 * This prevents custody deadlocks if a counterparty walks away or is unresponsive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferExpiryScheduler {

    private static final Duration TRANSFER_TTL = Duration.ofHours(2);

    private final TransferService transferService;

    @Scheduled(fixedRate = 5 * 60 * 1000) // Run every 5 minutes
    public void expireUnconfirmedTransfers() {
        try {
            int expiredCount = transferService.expireStaleTransfers(TRANSFER_TTL);
            if (expiredCount > 0) {
                log.info("TransferExpiryScheduler: auto-cancelled {} stale transfer(s) exceeding TTL ({})",
                        expiredCount, TRANSFER_TTL);
            }
        } catch (Exception e) {
            log.error("TransferExpiryScheduler: error while checking for stale transfers", e);
        }
    }
}
