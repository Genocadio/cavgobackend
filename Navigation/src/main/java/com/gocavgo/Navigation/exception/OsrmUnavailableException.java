package com.gocavgo.Navigation.exception;

/**
 * Thrown when the OSRM routing engine is unreachable (e.g. server down,
 * network failure, timeout). This is a transient infrastructure failure
 * rather than a client or application error, so it maps to HTTP 503.
 */
public class OsrmUnavailableException extends RuntimeException {

    public OsrmUnavailableException(String message) {
        super(message);
    }

    public OsrmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
