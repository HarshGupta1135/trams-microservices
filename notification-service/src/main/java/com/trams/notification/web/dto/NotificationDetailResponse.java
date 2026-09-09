package com.trams.notification.web.dto;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import com.trams.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/** Full representation of a single notification, including the rendered body. */
public record NotificationDetailResponse(
        UUID id,
        UUID eventId,
        String eventType,
        String recipientEmail,
        NotificationChannel channel,
        String subject,
        String body,
        NotificationStatus status,
        int attempts,
        String lastError,
        String correlationId,
        Instant createdAt,
        Instant sentAt) {

    public static NotificationDetailResponse from(Notification notification) {
        return new NotificationDetailResponse(
                notification.getId(),
                notification.getEventId(),
                notification.getEventType(),
                notification.getRecipientEmail(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getBody(),
                notification.getStatus(),
                notification.getAttempts(),
                notification.getLastError(),
                notification.getCorrelationId(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
