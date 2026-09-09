package com.trams.messaging;

/** Raised when a message body is not valid JSON, or does not satisfy the envelope contract. */
public class MalformedEventException extends PermanentEventException {

    public static final String REASON = "malformed-event";

    public MalformedEventException(String message, Throwable cause) {
        super(message, REASON, cause);
    }

    public MalformedEventException(String message) {
        super(message, REASON);
    }
}
