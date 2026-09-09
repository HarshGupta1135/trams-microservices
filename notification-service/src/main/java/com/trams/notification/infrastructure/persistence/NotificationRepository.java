package com.trams.notification.infrastructure.persistence;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Looks up the notification already recorded for an event. */
    Optional<Notification> findByEventId(UUID eventId);

    /** Recipient-facing history, newest first. */
    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(UUID recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
            UUID recipientUserId, NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);
}
