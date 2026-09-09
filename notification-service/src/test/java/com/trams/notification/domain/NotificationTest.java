package com.trams.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the notification's delivery state machine. */
class NotificationTest {

    private static final Instant CREATED = Instant.parse("2026-03-04T05:06:07Z");
    private static final UUID EVENT_ID = UUID.randomUUID();

    private static Notification pending() {
        return Notification.pending(
                EVENT_ID,
                "user.registered",
                UUID.randomUUID(),
                "someone@example.com",
                "Someone",
                NotificationChannel.EMAIL,
                "Welcome to TRAMS",
                "<p>Hello</p>",
                "corr-1",
                CREATED);
    }

    @Test
    @DisplayName("a new notification is pending, unattempted and undelivered")
    void startsPending() {
        Notification notification = pending();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getAttempts()).isZero();
        assertThat(notification.getSentAt()).isNull();
        assertThat(notification.isDelivered()).isFalse();
        // The event id is the idempotency key and must be carried verbatim.
        assertThat(notification.getEventId()).isEqualTo(EVENT_ID);
    }

    @Test
    @DisplayName("a successful send records the time and counts the attempt")
    void marksSent() {
        Notification notification = pending();

        notification.markSent(CREATED.plusSeconds(2));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.isDelivered()).isTrue();
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getSentAt()).isEqualTo(CREATED.plusSeconds(2));
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    @DisplayName("a retryable failure stays FAILED so a redelivery resumes it")
    void marksRetryableFailure() {
        // FAILED, not DEAD: the event will come back, and the handler needs to know this
        // one still deserves another delivery attempt.
        Notification notification = pending();

        notification.markAttemptFailed("relay refused", false, CREATED.plusSeconds(1));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getLastError()).isEqualTo("relay refused");
        assertThat(notification.isDelivered()).isFalse();
    }

    @Test
    @DisplayName("the final failure marks it DEAD, matching the dead-lettered event")
    void marksExhaustedFailure() {
        // On the last attempt the message is dead-lettered, so leaving the row looking
        // merely "will retry" would misrepresent it permanently.
        Notification notification = pending();

        notification.markAttemptFailed("attempt 1", false, CREATED.plusSeconds(1));
        notification.markAttemptFailed("attempt 2", false, CREATED.plusSeconds(3));
        notification.markAttemptFailed("gave up", true, CREATED.plusSeconds(9));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DEAD);
        assertThat(notification.getAttempts()).isEqualTo(3);
        assertThat(notification.getLastError()).isEqualTo("gave up");
    }

    @Test
    @DisplayName("a failed notification can still succeed on a later redelivery")
    void recoversAfterFailure() {
        Notification notification = pending();

        notification.markAttemptFailed("temporary glitch", false, CREATED.plusSeconds(1));
        notification.markSent(CREATED.plusSeconds(5));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).isEqualTo(2);
        // The stale error must not linger on a delivered notification.
        assertThat(notification.getLastError()).isNull();
    }

    @Test
    @DisplayName("an oversized error is truncated rather than failing the update")
    void truncatesLongErrors() {
        // A driver stack trace can exceed the column width. Losing the tail beats losing
        // the delivery bookkeeping entirely.
        Notification notification = pending();

        notification.markAttemptFailed("x".repeat(10_000), false, CREATED);

        assertThat(notification.getLastError()).hasSize(4_000);
    }

    @Test
    @DisplayName("toString omits the body, which carries personal data")
    void toStringExcludesBody() {
        // Notification bodies contain names and addresses; logs are retained longer and
        // read more widely than the notifications themselves.
        assertThat(pending().toString()).doesNotContain("Hello").contains("user.registered");
    }
}
