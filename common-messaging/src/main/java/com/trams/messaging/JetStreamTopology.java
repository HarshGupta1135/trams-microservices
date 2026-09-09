package com.trams.messaging;

import com.trams.contracts.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.StreamContext;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Declares broker topology as code and reconciles it at startup. */
public class JetStreamTopology {

    private static final Logger log = LoggerFactory.getLogger(JetStreamTopology.class);

    /** Kept below the broker's max_payload (1MB) so an oversized event fails fast. */
    private static final int MAX_MESSAGE_SIZE_BYTES = 512 * 1024;

    /** Dead letters outlive live events: they exist to be inspected and replayed. */
    private static final Duration DEAD_LETTER_RETENTION = Duration.ofDays(90);

    private final Connection connection;
    private final NatsProperties properties;

    public JetStreamTopology(Connection connection, NatsProperties properties) {
        this.connection = connection;
        this.properties = properties;
    }

    /** The stream of user domain events. */
    public StreamConfiguration userEventsStream() {
        NatsProperties.Stream stream = properties.stream();

        return StreamConfiguration.builder()
                .name(Subjects.STREAM_USER_EVENTS)
                .subjects(Subjects.USER_EVENTS_WILDCARD)
                .retentionPolicy(RetentionPolicy.Limits)
                .storageType(StorageType.File)
                .discardPolicy(DiscardPolicy.Old)
                .replicas(stream.replicas())
                .maxAge(stream.maxAge())
                // Broker-side deduplication window, keyed on the message id that the
                // publisher sets from the event id.
                .duplicateWindow(stream.duplicateWindow())
                .maximumMessageSize(MAX_MESSAGE_SIZE_BYTES)
                .maxMessages(5_000_000L)
                // Events are an audit trail; deleting individual messages is not a
                // supported operation on this stream.
                .denyDelete(true)
                .build();
    }

    /** The stream holding messages that could not be processed. */
    public StreamConfiguration deadLetterStream() {
        return StreamConfiguration.builder()
                .name(Subjects.STREAM_DEAD_LETTER)
                .subjects(Subjects.DEAD_LETTER_WILDCARD)
                .retentionPolicy(RetentionPolicy.Limits)
                .storageType(StorageType.File)
                .discardPolicy(DiscardPolicy.Old)
                .replicas(properties.stream().replicas())
                .maxAge(DEAD_LETTER_RETENTION)
                .maximumMessageSize(MAX_MESSAGE_SIZE_BYTES)
                .maxMessages(1_000_000L)
                // An operator must be able to purge dead letters once they are replayed.
                .denyDelete(false)
                .build();
    }

    /** Creates the stream, or updates an existing one to match the declared configuration. */
    public void ensureStream(StreamConfiguration desired) {
        try {
            JetStreamManagement management = connection.jetStreamManagement();

            if (streamExists(management, desired.getName())) {
                management.updateStream(desired);
                log.debug("Reconciled JetStream stream '{}'", desired.getName());
            } else {
                management.addStream(desired);
                log.info(
                        "Created JetStream stream '{}' on subjects {}",
                        desired.getName(),
                        desired.getSubjects());
            }
        } catch (JetStreamApiException e) {
            throw new IllegalStateException(
                    "The existing JetStream stream '%s' conflicts with the declared configuration: %s"
                            .formatted(desired.getName(), e.getMessage()),
                    e);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to reconcile JetStream stream " + desired.getName(), e);
        }
    }

    private boolean streamExists(JetStreamManagement management, String name) throws IOException {
        try {
            management.getStreamInfo(name);
            return true;
        } catch (JetStreamApiException e) {
            // 404 is the expected "not found"; anything else (notably a permissions
            // error) is a real problem and must not be mistaken for absence.
            if (e.getErrorCode() == 404) {
                return false;
            }
            throw new IllegalStateException(
                    "Could not read JetStream stream '%s': %s".formatted(name, e.getMessage()), e);
        }
    }

    /** Blocks until a stream owned by another service exists. */
    public StreamContext awaitStream(String streamName, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Duration wait = Duration.ofMillis(250);
        Exception lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                return connection.getStreamContext(streamName);
            } catch (IOException | JetStreamApiException e) {
                lastFailure = e;
                log.info(
                        "Stream '{}' is not available yet ({}); retrying in {}ms",
                        streamName,
                        e.getMessage(),
                        wait.toMillis());
                Thread.sleep(wait.toMillis());
                // Exponential backoff, capped so the log stays readable.
                wait = Duration.ofMillis(Math.min(5_000L, wait.toMillis() * 2));
            }
        }

        throw new IllegalStateException(
                "Stream '%s' did not become available within %s. Is the producing service running?"
                        .formatted(streamName, timeout),
                lastFailure);
    }
}
