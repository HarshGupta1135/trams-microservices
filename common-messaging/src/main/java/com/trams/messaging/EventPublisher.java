package com.trams.messaging;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.EventHeaders;
import com.trams.contracts.UserEventPayload;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes events to JetStream with acknowledgement, bounded retries and broker-side
 * deduplication.
 *
 * <p>Two properties make this reliable rather than best-effort:
 *
 * <ol>
 *   <li>{@code js.publish} returns only once the stream has <em>persisted</em> the
 *       message. A returned {@link PublishAck} is a durability guarantee, not a
 *       fire-and-forget hand-off.
 *   <li>The event id is sent as the message id, so a retry after an ambiguous failure
 *       (stored successfully, acknowledgement lost) is collapsed by the stream's duplicate
 *       window instead of producing a second event. This is what lets the outbox relay
 *       retry freely without risking duplicates.
 * </ol>
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final JetStream jetStream;
    private final EventCodec codec;
    private final String expectedStream;
    private final Duration publishTimeout;
    private final int maxAttempts;

    /**
     * @param expectedStream the stream this publisher writes to; asserted on every
     *     publish so a subject/stream misconfiguration is caught immediately rather than
     *     silently landing events somewhere unexpected
     */
    public EventPublisher(
            JetStream jetStream,
            EventCodec codec,
            String expectedStream,
            Duration publishTimeout,
            int maxAttempts) {
        this.jetStream = jetStream;
        this.codec = codec;
        this.expectedStream = expectedStream;
        this.publishTimeout = publishTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /**
     * An already-serialised event, ready to ship.
     *
     * <p>This is what the transactional outbox relay publishes: the envelope was
     * serialised when it was recorded, so the relay forwards the exact bytes that were
     * committed rather than re-serialising them. That keeps the relay free of any domain
     * knowledge — adding an event type never requires touching it — and removes the
     * possibility of a round-trip through Jackson altering the payload.
     *
     * @param id the event id, used as the broker's deduplication key
     */
    public record RawEvent(
            String id,
            String type,
            int dataVersion,
            String source,
            String subject,
            String correlationId,
            byte[] body) {}

    /**
     * Publishes a typed envelope. Convenience over {@link #publish(RawEvent)}, used by
     * tests and by any caller holding a live envelope.
     */
    public PublishAck publish(EventEnvelope<? extends UserEventPayload> envelope) {
        return publish(
                new RawEvent(
                        envelope.id().toString(),
                        envelope.type(),
                        envelope.dataVersion(),
                        envelope.source(),
                        envelope.subject(),
                        envelope.correlationId(),
                        codec.serialise(envelope)));
    }

    /**
     * @throws TransientEventException if the broker could not be reached after exhausting
     *     the attempt budget; the caller (the outbox relay) leaves the row pending and
     *     retries later, so the event is never lost
     * @throws IllegalStateException if the broker rejects the publish outright, which
     *     indicates a topology or permissions problem rather than a transient fault
     */
    public PublishAck publish(RawEvent event) {
        Headers headers = buildHeaders(event);

        PublishOptions options =
                PublishOptions.builder()
                        // Broker-side idempotency key.
                        .messageId(event.id())
                        // Fail loudly if the subject ever resolves to an unexpected stream.
                        .expectedStream(expectedStream)
                        .streamTimeout(publishTimeout)
                        .build();

        IOException lastTransportFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                PublishAck ack = jetStream.publish(event.subject(), headers, event.body(), options);

                if (ack.isDuplicate()) {
                    log.info(
                            "Event {} ({}) was already present in stream {}; deduplicated by the broker",
                            event.id(),
                            event.type(),
                            ack.getStream());
                } else {
                    log.debug(
                            "Published event {} ({}) to {} at sequence {}",
                            event.id(),
                            event.type(),
                            event.subject(),
                            ack.getSeqno());
                }

                return ack;
            } catch (IOException e) {
                // Transport-level problem: the broker may be restarting or the network
                // may have blipped. Worth retrying.
                lastTransportFailure = e;
                if (attempt < maxAttempts) {
                    long backoffMillis = backoffMillis(attempt);
                    log.warn(
                            "Publishing event {} failed on attempt {}/{} ({}); retrying in {}ms",
                            event.id(),
                            attempt,
                            maxAttempts,
                            e.getMessage(),
                            backoffMillis);
                    sleep(backoffMillis);
                }
            } catch (JetStreamApiException e) {
                // The broker answered and refused. Retrying an authorisation or
                // configuration error only produces noise, so fail immediately with a
                // message that names the likely cause.
                throw new IllegalStateException(
                        "The broker rejected event %s on subject '%s' (error %d): %s. Check the stream topology and this service's NATS permissions."
                                .formatted(event.id(), event.subject(), e.getErrorCode(), e.getErrorDescription()),
                        e);
            }
        }

        throw new TransientEventException(
                "Could not publish event %s after %d attempts".formatted(event.id(), maxAttempts),
                lastTransportFailure);
    }

    private static Headers buildHeaders(RawEvent event) {
        return new Headers()
                .put(EventHeaders.EVENT_ID, event.id())
                .put(EventHeaders.EVENT_TYPE, event.type())
                .put(EventHeaders.DATA_VERSION, String.valueOf(event.dataVersion()))
                .put(EventHeaders.SOURCE, event.source())
                .put(EventHeaders.CORRELATION_ID, event.correlationId());
    }

    /** Exponential backoff with jitter, so replicas do not retry in lock-step. */
    private static long backoffMillis(int attempt) {
        long exponential = Math.min(2_000L, 100L * (1L << (attempt - 1)));
        return exponential + (long) (Math.random() * exponential * 0.2);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientEventException("Interrupted while retrying a publish", e);
        }
    }
}
