package com.trams.user.domain;

public enum OutboxStatus {
    /** Committed to the database, not yet accepted by the broker. */
    PENDING,
    /** Persisted by the broker; the stream has acknowledged it. */
    PUBLISHED,
    /** Retries exhausted. */
    FAILED
}
