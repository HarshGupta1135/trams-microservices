package com.trams.user.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the outbox row's retry state machine. */
class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");
    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(5);

    private static OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "User",
                UUID.randomUUID(),
                "user.registered",
                1,
                "user.events.registered",
                "{\"id\":\"x\"}",
                "corr-1",
                NOW);
    }

    @Test
    @DisplayName("a new row is pending, unattempted and immediately due")
    void startsPendingAndDue() {
        OutboxEvent event = pendingEvent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isZero();
        // Due immediately: the relay should ship a freshly committed event without delay.
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("publishing records the time and clears any previous error")
    void publishingClearsError() {
        OutboxEvent event = pendingEvent();
        event.markAttemptFailed("broker unreachable", NOW, 8, BASE, MAX);

        event.markPublished(NOW.plusSeconds(5));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW.plusSeconds(5));
        // A stale error on a published row would mislead anyone investigating later.
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("each failure doubles the delay before the next attempt")
    void backsOffExponentially() {
        OutboxEvent event = pendingEvent();

        event.markAttemptFailed("attempt 1", NOW, 8, BASE, MAX);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        event.markAttemptFailed("attempt 2", NOW, 8, BASE, MAX);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));

        event.markAttemptFailed("attempt 3", NOW, 8, BASE, MAX);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("the delay is capped, so a long outage does not defer an event for days")
    void capsTheBackoff() {
        OutboxEvent event = pendingEvent();

        for (int i = 0; i < 12; i++) {
            event.markAttemptFailed("still failing", NOW, 100, BASE, MAX);
        }

        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plus(MAX));
    }

    @Test
    @DisplayName("the row is marked FAILED once its attempt budget is spent")
    void stopsRetryingAtMaxAttempts() {
        // Retrying forever would hide a genuine outage behind an ever-growing backlog.
        // FAILED makes it visible to the metric and to an operator.
        OutboxEvent event = pendingEvent();

        for (int i = 0; i < 3; i++) {
            event.markAttemptFailed("permanent problem", NOW, 3, BASE, MAX);
        }

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getLastError()).isEqualTo("permanent problem");
    }

    @Test
    @DisplayName("an oversized error message is truncated rather than failing the write")
    void truncatesLongErrors() {
        // A driver stack trace can exceed the column width; losing the tail of a message
        // is preferable to the UPDATE failing and losing the retry bookkeeping entirely.
        OutboxEvent event = pendingEvent();

        event.markAttemptFailed("x".repeat(10_000), NOW, 8, BASE, MAX);

        assertThat(event.getLastError()).hasSize(4_000);
    }

    @Test
    @DisplayName("the row id is the event id, which is the broker's deduplication key")
    void idIsTheEventId() {
        UUID eventId = UUID.randomUUID();

        OutboxEvent event =
                OutboxEvent.pending(
                        eventId, "User", UUID.randomUUID(), "user.deleted", 1, "user.events.deleted", "{}", "c", NOW);

        // The relay passes this id to JetStream as the message id; if it diverged from
        // the envelope's id, deduplication would silently stop working.
        assertThat(event.getId()).isEqualTo(eventId);
    }
}
