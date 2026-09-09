package com.trams.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the transactional outbox: an event that has been committed to the database
 * and is awaiting publication to the broker.
 *
 * <p>The row is inserted in the same transaction as the domain change it describes, which
 * is what makes publishing reliable. Either both are durable or neither is; there is no
 * interleaving in which a user is created without its event, or an event escapes for a
 * transaction that rolled back.
 *
 * <p>{@code id} is the event id from the envelope, and the relay passes it to the broker
 * as the deduplication key. That is what makes a redundant republish — the relay crashed
 * after the broker stored the message but before this row was marked published — harmless.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** Also the envelope's event id, and therefore the broker's deduplication key. */
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 128)
    private String eventType;

    /** Schema version of the payload, mirrored from the envelope. */
    @Column(name = "data_version", nullable = false, updatable = false)
    private int dataVersion;

    @Column(nullable = false, updatable = false, length = 256)
    private String subject;

    /**
     * The complete serialised envelope. Storing the finished payload keeps the relay free
     * of domain knowledge: it ships bytes and records the outcome, so adding an event type
     * never requires touching the relay.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    private OutboxEvent(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int dataVersion,
            String subject,
            String payload,
            String correlationId,
            Instant now) {

        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.dataVersion = dataVersion;
        this.subject = subject;
        this.payload = payload;
        this.correlationId = correlationId;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public static OutboxEvent pending(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            int dataVersion,
            String subject,
            String payload,
            String correlationId,
            Instant now) {

        return new OutboxEvent(
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                dataVersion,
                subject,
                payload,
                correlationId,
                now);
    }

    /** The broker has durably stored this event. */
    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * Records a failed publish and schedules the next attempt.
     *
     * @param maxAttempts once reached, the row is marked {@link OutboxStatus#FAILED} and
     *     left for an operator: silently retrying forever would hide a genuine outage.
     */
    public void markAttemptFailed(String error, Instant now, int maxAttempts, Duration baseBackoff, Duration maxBackoff) {
        this.attempts += 1;
        this.lastError = truncate(error, 4_000);

        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
            this.nextAttemptAt = now;
            return;
        }

        long exponent = Math.min(this.attempts - 1, 20);
        long delayMillis = Math.min(maxBackoff.toMillis(), baseBackoff.toMillis() * (1L << exponent));
        this.nextAttemptAt = now.plusMillis(delayMillis);
    }


    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getDataVersion() {
        return dataVersion;
    }

    public String getSubject() {
        return subject;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public String toString() {
        return "OutboxEvent[id=%s, type=%s, status=%s, attempts=%d]"
                .formatted(id, eventType, status, attempts);
    }
}
