package com.trams.notification.infrastructure.delivery;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;

/** Outbound delivery port. */
public interface NotificationSender {

    /** The channel this sender delivers over. */
    NotificationChannel channel();

    /** Whether this channel is actually usable in the current configuration. */
    default boolean isAvailable() {
        return true;
    }

    /** Attempts delivery. */
    void send(Notification notification) throws DeliveryException;
}
