package com.gocavgo.delivary.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Installs a JVM-wide default uncaught exception handler so that any throwable
 * escaping on any thread (scheduled, async, MQ consumer, web worker ...) is
 * always surfaced in the logs instead of dying silently in the JVM default
 * handler. Catches what the framework does not; it never changes behaviour,
 * it only guarantees visibility.
 */
@Slf4j
@Component
public class GlobalUncaughtExceptionLogger {

    @PostConstruct
    public void registerHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isBenignClosedWebSocketException(throwable)) {
                log.debug("Ignoring closed WebSocket session exception on thread '{}': {}",
                        thread.getName(), throwable.getMessage());
                return;
            }
            log.error("Uncaught exception on thread '{}': {}", thread.getName(),
                    throwable.getMessage(), throwable);
        });
        log.info("Global uncaught exception handler installed");
    }

    private boolean isBenignClosedWebSocketException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String msg = current.getMessage();
            if (current instanceof IllegalStateException
                    && msg != null
                    && msg.contains("The WebSocket session")
                    && msg.contains("has been closed")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}