package com.trams.notification.domain;

/** Delivery transport. */
public enum NotificationChannel {
    EMAIL,
    /** Writes the rendered notification to the log; used when no relay is configured. */
    LOG
}
