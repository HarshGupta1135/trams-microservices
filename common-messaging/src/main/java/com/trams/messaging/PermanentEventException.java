package com.trams.messaging;

/**
 * Signals a failure that no number of retries can fix - a payload that fails schema validation,
 * an unknown event type, a business rule that rejects the event.
 */
public class PermanentEventException extends RuntimeException {

    private final String reason;

    public PermanentEventException(String message, String reason) {
        this(message, reason, null);
    }

    public PermanentEventException(String message, String reason, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
