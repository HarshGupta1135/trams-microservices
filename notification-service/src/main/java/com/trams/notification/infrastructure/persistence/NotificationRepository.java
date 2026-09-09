package com.trams.notification.infrastructure.persistence;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Looks up the notification already recorded for an event.
     *
     * <p>This is the read side of the idempotency check: if a row exists, this event has
     * been seen before and its status says whether the work finished.
     */
    Optional<Notification> findByEventId(UUID eventId);

    /** Recipient-facing history, newest first. */
    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            UUID recipientUserId, NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);
}
