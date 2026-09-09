package com.trams.notification.domain;

/**
 * A rendered notification, ready to be delivered.
 *
 * <p>Composition is separated from delivery so that rendering can be unit-tested without
 * a mail server, and so the same content can be delivered over a different channel
 * without re-rendering it.
 */
public record NotificationContent(String subject, String body) {}
