package com.gocavgo.delivary.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side effects (e.g. real-time event publishes) only after the current
 * transaction commits. Without this, a rollback would leave subscribers with
 * events for database changes that never happened.
 */
public final class AfterCommitActions {

    private AfterCommitActions() {
    }

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
