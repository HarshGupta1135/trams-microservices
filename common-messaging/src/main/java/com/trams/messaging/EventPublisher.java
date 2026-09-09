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
 */
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final JetStream jetStream;
    private final EventCodec codec;
    private final String expectedStream;
    private final Duration publishTimeout;
    private final int maxAttempts;

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

    /** An already-serialised event, ready to ship. */
    public record RawEvent(
            String id,
            String type,
            int dataVersion,
            String source,
            String subject,
            String correlationId,
            byte[] body) {}

    /** Publishes a typed envelope. */
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
                // The broker answered and refused.
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
