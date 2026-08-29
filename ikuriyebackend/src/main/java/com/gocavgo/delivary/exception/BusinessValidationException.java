package com.gocavgo.delivary.exception;

/**
 * Thrown when a business rule is violated (e.g. package already in an open transfer,
 * transfer not in expected status, etc.). These are expected client-facing errors,
 * not unexpected server errors.
 */
public class BusinessValidationException extends RuntimeException {

    public BusinessValidationException(String message) {
        super(message);
    }

    public BusinessValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
