package com.trams.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests the redelivery backoff ladder. */
class ConsumerSettingsTest {

    private static DurableEventConsumer.ConsumerSettings settings(long maxDeliver, Duration base, Duration max) {
        return new DurableEventConsumer.ConsumerSettings(
                "STREAM", "consumer", "subject.>", Duration.ofSeconds(30), maxDeliver, 256, 25, 2, base, max);
    }

    @Test
    @DisplayName("the ladder has one delay per redelivery, not per delivery")
    void ladderHasOneEntryPerRedelivery() {
        // With 5 delivery attempts there are only 4 gaps between them. An off-by-one
        // here would either waste the last attempt or leave JetStream without a delay.
        var ladder = settings(5, Duration.ofSeconds(2), Duration.ofSeconds(60)).backoffLadder();

        assertThat(ladder).hasSize(4);
    }

    @Test
    @DisplayName("delays double until they reach the cap, then stay there")
    void delaysDoubleThenSaturate() {
        var ladder = settings(6, Duration.ofSeconds(2), Duration.ofSeconds(10)).backoffLadder();

        assertThat(ladder)
                .containsExactly(
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8),
                        // Capped: 16s would exceed the configured maximum.
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a single delivery attempt still yields a usable ladder")
    void singleAttemptStillProducesALadder() {
        // JetStream rejects an empty backoff array, so the ladder must never be empty
        // even when redelivery is effectively disabled.
        var ladder = settings(1, Duration.ofSeconds(2), Duration.ofSeconds(60)).backoffLadder();

        assertThat(ladder).hasSize(1);
    }

    @Test
    @DisplayName("a long ladder cannot overflow into negative delays")
    void doesNotOverflowOnLongLadders() {
        // The shift is bounded so a large maxDeliver cannot wrap a long and produce a
        // negative duration, which the broker would reject at consumer creation.
        var ladder = settings(40, Duration.ofSeconds(1), Duration.ofMinutes(5)).backoffLadder();

        assertThat(ladder).hasSize(39);
        assertThat(ladder).allSatisfy(delay -> assertThat(delay).isPositive());
        assertThat(ladder).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(Duration.ofMinutes(5)));
    }
}
