package com.trams.notification.domain;

public enum NotificationStatus {
    /** Recorded but not yet delivered. */
    PENDING,
    /** Delivered successfully. */
    SENT,
    /** A delivery attempt failed transiently; the event will be redelivered. */
    FAILED,
    /** Delivery attempts are exhausted. */
    DEAD
}
