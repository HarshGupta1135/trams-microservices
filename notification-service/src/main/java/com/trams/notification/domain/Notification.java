package com.trams.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification that was composed for a recipient, and the record of what happened to it.
 *
 * <p>This entity doubles as the idempotency record for the event that produced it: the
 * unique {@code eventId} means a redelivered event cannot create a second notification,
 * and {@code status} says how far the previous attempt got so a retry can resume instead
 * of duplicating a send.
 *
 * <p>Recipient details are copied from the event rather than looked up. A notification is
 * a historical record of what was sent to which address, so it must not change
 * retroactively when the user later edits their profile — and this service has no way to
 * query the User Service anyway, by design.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Idempotency key: the id of the event that caused this notification. */
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 128)
    private String eventType;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_email", nullable = false, updatable = false, length = 320)
    private String recipientEmail;

    @Column(name = "recipient_name", nullable = false, updatable = false, length = 200)
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationChannel channel;

    @Column(nullable = false, updatable = false, length = 255)
    private String subject;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {}

    private Notification(
            UUID eventId,
            String eventType,
            UUID recipientUserId,
            String recipientEmail,
            String recipientName,
            NotificationChannel channel,
            String subject,
            String body,
            String correlationId,
            Instant now) {

        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.eventType = eventType;
        this.recipientUserId = recipientUserId;
        this.recipientEmail = recipientEmail;
        this.recipientName = recipientName;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.correlationId = correlationId;
        this.status = NotificationStatus.PENDING;
        this.attempts = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Records a notification that is composed but not yet delivered. */
    public static Notification pending(
            UUID eventId,
            String eventType,
            UUID recipientUserId,
            String recipientEmail,
            String recipientName,
            NotificationChannel channel,
            String subject,
            String body,
            String correlationId,
            Instant now) {

        return new Notification(
                eventId,
                eventType,
                recipientUserId,
                recipientEmail,
                recipientName,
                channel,
                subject,
                body,
                correlationId,
                now);
    }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.attempts += 1;
        this.sentAt = now;
        this.updatedAt = now;
        this.lastError = null;
    }

    /**
     * Records a failed attempt.
     *
     * @param exhausted true when no further redelivery will occur, in which case the
     *     notification is marked {@link NotificationStatus#DEAD} so it is visible as
     *     permanently undelivered rather than looking merely "in progress" forever
     */
    public void markAttemptFailed(String error, boolean exhausted, Instant now) {
        this.attempts += 1;
        this.lastError = truncate(error, 4_000);
        this.status = exhausted ? NotificationStatus.DEAD : NotificationStatus.FAILED;
        this.updatedAt = now;
    }

    public boolean isDelivered() {
        return status == NotificationStatus.SENT;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getRecipientUserId() {
        return recipientUserId;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Notification notification)) return false;
        return id != null && id.equals(notification.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }

    /** Excludes the body, which can be large and may contain personal data. */
    @Override
    public String toString() {
        return "Notification[id=%s, eventId=%s, type=%s, channel=%s, status=%s, attempts=%d]"
                .formatted(id, eventId, eventType, channel, status, attempts);
    }
}
