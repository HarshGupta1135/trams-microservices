package com.trams.notification.domain;

/** A rendered notification, ready to be delivered. */
public record NotificationContent(String subject, String body) {}
