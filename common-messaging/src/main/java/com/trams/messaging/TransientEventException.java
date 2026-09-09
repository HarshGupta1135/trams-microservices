package com.trams.messaging;

import java.time.Duration;

/**
 * Signals a failure that is expected to succeed on a later attempt - a database restarting, an
 * SMTP timeout, a downstream service briefly unavailable.
 */
public class TransientEventException extends RuntimeException {

    private final Duration retryAfter;

    public TransientEventException(String message) {
        this(message, null, null);
    }

    public TransientEventException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public TransientEventException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /** The requested redelivery delay, or null to use the configured backoff. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
