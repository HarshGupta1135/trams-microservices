package com.trams.notification.domain;

import java.util.UUID;

/** Raised when a notification does not exist, or belongs to a different recipient. */
public class NotificationNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "NOTIFICATION_NOT_FOUND";

    public NotificationNotFoundException(UUID notificationId) {
        super("No notification exists with id " + notificationId);
    }
}
