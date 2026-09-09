package com.trams.user.infrastructure.outbox;

import com.trams.contracts.Sources;
import com.trams.messaging.EventPublisher;
import com.trams.user.config.OutboxProperties;
import com.trams.user.domain.OutboxEvent;
import com.trams.user.domain.OutboxStatus;
import com.trams.user.infrastructure.persistence.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Moves committed events from the outbox to the broker. */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outbox;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(
            OutboxEventRepository outbox,
            EventPublisher publisher,
            OutboxProperties properties,
            MeterRegistry meters) {

        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;

        this.published =
                Counter.builder("trams.outbox.published")
                        .description("Events successfully published to the broker")
                        .register(meters);
        this.failed =
                Counter.builder("trams.outbox.publish.failures")
                        .description("Failed publish attempts")
                        .register(meters);

        // Backlog depth and age are the two signals that matter operationally: a growing
        // backlog means the broker or the relay is unhealthy, and events are being delayed.
        Gauge.builder("trams.outbox.pending", () -> outbox.countByStatus(OutboxStatus.PENDING))
                .description("Events awaiting publication")
                .register(meters);
        Gauge.builder("trams.outbox.failed", () -> outbox.countByStatus(OutboxStatus.FAILED))
                .description("Events that exhausted their retry budget")
                .register(meters);
        Gauge.builder("trams.outbox.oldest.pending.age.seconds", this::oldestPendingAgeSeconds)
                .description("Age of the oldest unpublished event, in seconds")
                .register(meters);
    }

    /** Publishes one batch of due events. */
    @Scheduled(fixedDelayString = "${trams.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.claimDueBatch(properties.batchSize());

        if (batch.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        int succeeded = 0;

        for (OutboxEvent row : batch) {
            try {
                publisher.publish(toRawEvent(row));
                row.markPublished(now);
                published.increment();
                succeeded++;
            } catch (RuntimeException e) {
                // Both transient (broker unreachable) and permanent (rejected) failures land
                // here.
                row.markAttemptFailed(
                        e.getMessage(),
                        now,
                        properties.maxAttempts(),
                        properties.baseBackoff(),
                        properties.maxBackoff());

                failed.increment();

                log.warn(
                        "Failed to publish outbox event {} ({}), attempt {}/{}: {}",
                        row.getId(),
                        row.getEventType(),
                        row.getAttempts(),
                        properties.maxAttempts(),
                        e.getMessage());
            }
        }

        // Managed entities; the transaction flushes their state changes on commit.
        if (succeeded > 0) {
            log.debug("Relayed {}/{} outbox event(s)", succeeded, batch.size());
        }
    }

    private static EventPublisher.RawEvent toRawEvent(OutboxEvent row) {
        return new EventPublisher.RawEvent(
                row.getId().toString(),
                row.getEventType(),
                row.getDataVersion(),
                Sources.USER_SERVICE,
                row.getSubject(),
                row.getCorrelationId(),
                // The exact bytes committed with the domain change.
                row.getPayload().getBytes(StandardCharsets.UTF_8));
    }

    /** Removes published rows once they are older than the retention window. */
    @Scheduled(cron = "${trams.outbox.purge-cron:0 15 3 * * *}")
    @Transactional
    public void purgePublished() {
        Instant cutoff = Instant.now().minus(properties.retention());
        long removed = outbox.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, cutoff);

        if (removed > 0) {
            log.info("Purged {} published outbox row(s) older than {}", removed, cutoff);
        }
    }

    private double oldestPendingAgeSeconds() {
        Instant oldest = outbox.oldestPendingCreatedAt();
        return oldest == null ? 0d : (double) (Instant.now().toEpochMilli() - oldest.toEpochMilli()) / 1000d;
    }
}
