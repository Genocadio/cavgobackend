package com.gocavgo.delivary.dto.transfer.output;

/**
 * Event payload published when a new transfer is created.
 * Used by the {@code transferCreated} subscription to push real-time
 * notifications to the target acceptor (driver or worker).
 */
public record TransferEvent(
        TransferResponse transfer
) {
}
