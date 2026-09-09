package com.trams.notification.domain;

/**
 * Delivery transport.
 *
 * <p>Modelled as a first-class value rather than assumed, so adding SMS or a push channel
 * later means adding an enum constant and a sender implementation - not reworking the
 * notification record.
 */
public enum NotificationChannel {
    EMAIL,
    /** Writes the rendered notification to the log; used when no relay is configured. */
    LOG
}
