package com.gocavgo.delivary.security;

public class JwtAuthenticationException extends RuntimeException {

    public enum Reason {
        INVALID_TOKEN,
        EXPIRED_TOKEN,
        MALFORMED_TOKEN,
        USER_DISABLED
    }

    private final Reason reason;

    public JwtAuthenticationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public JwtAuthenticationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
