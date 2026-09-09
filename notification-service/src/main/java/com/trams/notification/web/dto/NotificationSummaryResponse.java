package com.trams.notification.web.dto;

import com.trams.notification.domain.Notification;
import com.trams.notification.domain.NotificationChannel;
import com.trams.notification.domain.NotificationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * List representation of a notification.
 *
 * <p>The rendered body is deliberately omitted: bodies are multi-kilobyte HTML documents,
 * and returning twenty of them per page would make a history request far heavier than it
 * needs to be. Fetch a single notification to read its content.
 */
public record NotificationSummaryResponse(
        UUID id,
        UUID eventId,
        String eventType,
        NotificationChannel channel,
        String subject,
        NotificationStatus status,
        int attempts,
        Instant createdAt,
        Instant sentAt) {

    public static NotificationSummaryResponse from(Notification notification) {
        return new NotificationSummaryResponse(
                notification.getId(),
                notification.getEventId(),
                notification.getEventType(),
                notification.getChannel(),
                notification.getSubject(),
                notification.getStatus(),
                notification.getAttempts(),
                notification.getCreatedAt(),
                notification.getSentAt());
    }
}
