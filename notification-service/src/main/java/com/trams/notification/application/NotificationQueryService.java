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

/** Read access to notification history. */
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

    /** Fetches one notification belonging to the given recipient. */
    @Transactional(readOnly = true)
    public Notification requireOwned(UUID notificationId, UUID recipientUserId) {
        return notifications
                .findById(notificationId)
                .filter(notification -> notification.getRecipientUserId().equals(recipientUserId))
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }
}
