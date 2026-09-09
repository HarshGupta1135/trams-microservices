package com.trams.notification.application;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationNotFoundException;
import com.trams.notification.domain.NotificationStatus;
import com.trams.notification.infrastructure.persistence.NotificationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to notification history.
 *
 * <p>Every query is scoped to a recipient id taken from the caller's verified token. There
 * is no method here that fetches a notification by id alone, which is deliberate: an
 * ownership check that has to be remembered at each call site is one that will eventually
 * be forgotten. Making the recipient a required parameter moves that guarantee into the
 * type signature.
 */
@Service
public class NotificationQueryService {

    private final NotificationRepository notifications;

    public NotificationQueryService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** A page of the recipient's own notifications, newest first. */
    @Transactional(readOnly = true)
    public Page<Notification> forRecipient(UUID recipientUserId, NotificationStatus status, Pageable pageable) {
        if (status == null) {
            return notifications.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId, pageable);
        }

        return notifications.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(
                recipientUserId, status, pageable);
    }

    /**
     * Fetches one notification belonging to the given recipient.
     *
     * <p>A notification owned by somebody else raises the same not-found error as one that
     * does not exist. Returning 403 instead would confirm that the id is real, letting an
     * attacker enumerate valid identifiers and infer other users' activity — the
     * insecure-direct-object-reference pattern. From the caller's perspective, a
     * notification they cannot see simply does not exist.
     *
     * @throws NotificationNotFoundException if it is missing, or belongs to another user
     */
    @Transactional(readOnly = true)
    public Notification requireOwned(UUID notificationId, UUID recipientUserId) {
        return notifications
                .findById(notificationId)
                .filter(notification -> notification.getRecipientUserId().equals(recipientUserId))
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }
}
