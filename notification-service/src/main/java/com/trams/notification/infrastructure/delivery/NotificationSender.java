package com.trams.notification.infrastructure.delivery;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;

/**
 * Outbound delivery port.
 *
 * <p>An interface rather than a direct dependency on {@code JavaMailSender}: the
 * application layer decides *what* to notify, and this abstraction decides *how*. That is
 * what allows the SMTP transport to be swapped for a log writer in a test - or for a push
 * or SMS provider later - without touching the event-handling logic.
 */
public interface NotificationSender {

    /** The channel this sender delivers over. */
    NotificationChannel channel();

    /**
     * Whether this channel is actually usable in the current configuration.
     *
     * <p>Checked once at startup so a misconfigured channel fails fast, rather than
     * dead-lettering every notification it is asked to deliver.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Attempts delivery.
     *
     * @throws DeliveryException on a transient failure; the caller then leaves the event
     *     unacknowledged so it is redelivered with backoff
     */
    void send(Notification notification) throws DeliveryException;
}
