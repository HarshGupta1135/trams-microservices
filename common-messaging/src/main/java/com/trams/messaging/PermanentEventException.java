package com.trams.messaging;

/**
 * Signals a failure that no number of retries can fix - a payload that fails schema
 * validation, an unknown event type, a business rule that rejects the event outright.
 *
 * <p>The message is terminated immediately and copied to the dead-letter stream. Retrying
 * would burn the delivery budget and delay healthy messages behind a poison one.
 */
public class PermanentEventException extends RuntimeException {

    private final String reason;

    /**
     * @param reason short, stable slug recorded on the dead-lettered message, e.g.
     *     {@code unknown-event-type}. Kept machine-readable so dead letters can be
     *     grouped and alerted on by cause.
     */
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
