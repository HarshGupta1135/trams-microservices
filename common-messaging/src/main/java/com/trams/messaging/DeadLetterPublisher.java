package com.trams.messaging;

import com.trams.contracts.EventHeaders;
import com.trams.contracts.Subjects;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies unprocessable messages into a durable dead-letter stream.
 *
 * <p>JetStream stops redelivering a message once {@code max_deliver} is reached, but it
 * does not keep it anywhere a human can find it. Explicitly forwarding the original bytes
 * — together with the reason, origin subject and delivery count — turns a silent drop into
 * an auditable record that can be inspected and replayed after a fix ships.
 */
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    private final JetStream jetStream;
    private final String consumerName;

    public DeadLetterPublisher(JetStream jetStream, String consumerName) {
        this.jetStream = jetStream;
        this.consumerName = consumerName;
    }

    /**
     * @throws TransientEventException if the dead letter itself cannot be stored. The
     *     caller must then leave the original message unacknowledged: redelivering it is
     *     strictly better than dropping it with no record anywhere.
     */
    public void publish(Message message, String reason, Throwable cause) {
        String subject = Subjects.deadLetter(consumerName);
        Headers headers = buildHeaders(message, reason);

        try {
            jetStream.publish(
                    subject,
                    headers,
                    message.getData(),
                    io.nats.client.PublishOptions.builder().streamTimeout(PUBLISH_TIMEOUT).build());

            log.error(
                    "Dead-lettered a message from '{}' to '{}' after {} deliveries. Reason: {}",
                    message.getSubject(),
                    subject,
                    deliveredCount(message),
                    reason,
                    cause);
        } catch (IOException | JetStreamApiException e) {
            log.error(
                    "Failed to publish a dead letter to '{}' (reason was '{}'). The original message will be redelivered instead.",
                    subject,
                    reason,
                    e);
            throw new TransientEventException("Could not store dead letter on " + subject, e);
        }
    }

    private Headers buildHeaders(Message message, String reason) {
        Headers headers =
                new Headers()
                        .put(EventHeaders.DLQ_REASON, reason)
                        .put(EventHeaders.DLQ_ORIGIN_SUBJECT, message.getSubject())
                        .put(EventHeaders.DLQ_CONSUMER, consumerName)
                        .put(EventHeaders.DLQ_DELIVERY_COUNT, String.valueOf(deliveredCount(message)))
                        .put(EventHeaders.DLQ_FAILED_AT, Instant.now().toString());

        // Preserve the original tracing headers so a dead letter can still be correlated
        // with the HTTP request that produced the event.
        Headers original = message.getHeaders();
        if (original != null) {
            copyIfPresent(original, headers, EventHeaders.EVENT_ID);
            copyIfPresent(original, headers, EventHeaders.EVENT_TYPE);
            copyIfPresent(original, headers, EventHeaders.CORRELATION_ID);
            copyIfPresent(original, headers, EventHeaders.SOURCE);
        }

        return headers;
    }

    private static void copyIfPresent(Headers from, Headers to, String key) {
        String value = from.getFirst(key);
        if (value != null && !value.isBlank()) {
            to.put(key, value);
        }
    }

    private static long deliveredCount(Message message) {
        return message.isJetStream() ? message.metaData().deliveredCount() : 0L;
    }
}
