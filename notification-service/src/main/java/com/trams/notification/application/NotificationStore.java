package com.trams.notification.application;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import com.trams.notification.domain.NotificationContent;
import com.trams.notification.infrastructure.persistence.NotificationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional persistence for notifications, and the enforcement point for idempotency.
 *
 * <p>Each method is its own short transaction, deliberately. Delivery (an SMTP
 * conversation) happens <em>between</em> {@link #claim} and {@link #markSent}, outside any
 * transaction: holding a database transaction open across a network call to a third party
 * would pin a connection for the duration of someone else's latency, and a slow relay
 * would exhaust the pool.
 *
 * <p>The cost of that choice is a window where a crash leaves a row {@code PENDING} with
 * the email possibly sent. That is the correct trade-off for this domain — the event will
 * be redelivered and the recipient may receive a duplicate notification, which is far
 * preferable to holding database connections hostage to SMTP, and strictly better than the
 * alternative failure mode of never sending at all.
 */
@Service
public class NotificationStore {

    private static final Logger log = LoggerFactory.getLogger(NotificationStore.class);

    private final NotificationRepository notifications;

    public NotificationStore(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /**
     * Claims an event for processing, recording the composed notification.
     *
     * <p>This is where at-least-once delivery becomes exactly-once <em>effect</em>. Three
     * cases are possible:
     *
     * <ul>
     *   <li>No row exists → insert one and return it, so delivery proceeds.
     *   <li>A row exists and is already {@code SENT} → return empty. The event is a
     *       redelivery of work that finished; the caller acknowledges and does nothing.
     *   <li>A row exists but is not sent → return it, so a previously failed delivery is
     *       retried rather than skipped.
     * </ul>
     *
     * <p>The insert can also lose a race against another replica handling the same
     * message. The unique constraint on {@code event_id} makes the database the arbiter:
     * the loser catches the violation and re-reads, converging on the same three cases
     * above. Correctness therefore does not depend on the two replicas coordinating.
     *
     * @return the notification to deliver, or empty if it was already delivered
     */
    @Transactional
    public Optional<Notification> claim(
            EventEnvelope<?> envelope,
            UserEventPayload payload,
            NotificationContent content,
            NotificationChannel channel) {

        Optional<Notification> existing = notifications.findByEventId(envelope.id());

        if (existing.isPresent()) {
            return alreadySeen(existing.get(), envelope);
        }

        Notification notification =
                Notification.pending(
                        envelope.id(),
                        envelope.type(),
                        payload.userId(),
                        payload.email(),
                        payload.fullName(),
                        channel,
                        content.subject(),
                        content.body(),
                        envelope.correlationId(),
                        Instant.now());

        try {
            // Flushed immediately so a unique-constraint violation surfaces here, where
            // it can be interpreted, rather than at commit.
            notifications.saveAndFlush(notification);
            return Optional.of(notification);

        } catch (DataIntegrityViolationException e) {
            // Another replica inserted the same event id first.
            log.debug("Lost the insert race for event {}; re-reading", envelope.id());

            return notifications
                    .findByEventId(envelope.id())
                    .flatMap(concurrent -> alreadySeen(concurrent, envelope))
                    // The row exists but is not visible to this transaction; a
                    // redelivery will resolve it.
                    .or(Optional::empty);
        }
    }

    private Optional<Notification> alreadySeen(Notification existing, EventEnvelope<?> envelope) {
        if (existing.isDelivered()) {
            log.info(
                    "Event {} was already delivered as notification {}; skipping (duplicate delivery)",
                    envelope.id(),
                    existing.getId());
            return Optional.empty();
        }

        log.info(
                "Event {} was seen before but not delivered (status {}); retrying notification {}",
                envelope.id(),
                existing.getStatus(),
                existing.getId());

        return Optional.of(existing);
    }

    @Transactional
    public void markSent(UUID notificationId) {
        notifications
                .findById(notificationId)
                .ifPresent(notification -> notification.markSent(Instant.now()));
    }

    /**
     * @param exhausted true on the final delivery attempt, which marks the notification
     *     permanently undelivered rather than leaving it as retryable
     */
    @Transactional
    public void markFailed(UUID notificationId, String error, boolean exhausted) {
        notifications
                .findById(notificationId)
                .ifPresent(notification -> notification.markAttemptFailed(error, exhausted, Instant.now()));
    }
}
