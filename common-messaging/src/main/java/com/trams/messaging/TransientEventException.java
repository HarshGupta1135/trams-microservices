package com.trams.messaging;

import java.time.Duration;

/**
 * Signals a failure that is expected to succeed on a later attempt - a database
 * restarting, an SMTP timeout, a downstream service briefly unavailable.
 *
 * <p>The message is negatively acknowledged and redelivered according to the consumer's
 * backoff ladder. Throwing this is how a handler says "not now" rather than "never".
 */
public class TransientEventException extends RuntimeException {

    private final Duration retryAfter;

    public TransientEventException(String message) {
        this(message, null, null);
    }

    public TransientEventException(String message, Throwable cause) {
        this(message, cause, null);
    }

    /**
     * @param retryAfter overrides the consumer's configured backoff for this delivery;
     *     useful when a dependency tells us exactly how long to wait
     */
    public TransientEventException(String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /** The requested redelivery delay, or {@code null} to use the configured backoff. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
