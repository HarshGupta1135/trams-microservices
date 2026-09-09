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

/** Transactional persistence for notifications, and the enforcement point for idempotency. */
@Service
public class NotificationStore {

    private static final Logger log = LoggerFactory.getLogger(NotificationStore.class);

    private final NotificationRepository notifications;

    public NotificationStore(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** Claims an event for processing, recording the composed notification. */
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

    @Transactional
    public void markFailed(UUID notificationId, String error, boolean exhausted) {
        notifications
                .findById(notificationId)
                .ifPresent(notification -> notification.markAttemptFailed(error, exhausted, Instant.now()));
    }
}
