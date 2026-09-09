package com.trams.user.domain;

public enum OutboxStatus {
    /** Committed to the database, not yet accepted by the broker. */
    PENDING,
    /** Persisted by the broker; the stream has acknowledged it. */
    PUBLISHED,
    /**
     * Retries exhausted. The row is kept deliberately: it is the audit trail of an event
     * the system failed to deliver, and can be replayed once the cause is fixed.
     */
    FAILED
}
