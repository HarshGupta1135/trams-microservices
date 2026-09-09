package com.trams.messaging;

import com.trams.contracts.EventEnvelope;
import io.nats.client.ConsumeOptions;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.StreamContext;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.ReplayPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;

/**
 * Durable, pull-based JetStream consumer with at-least-once delivery, bounded retries and
 * dead-lettering.
 */
public class DurableEventConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DurableEventConsumer.class);

    /** MDC key so every log line emitted while handling an event carries the trace id. */
    private static final String MDC_CORRELATION_ID = "correlationId";

    private static final String MDC_EVENT_ID = "eventId";

    /** Delivery metadata for the current attempt. */
    public record DeliveryContext(long attempt, long maxDeliver, boolean lastAttempt) {}

    /** Handles one parsed event. */
    @FunctionalInterface
    public interface EventHandler {
        void handle(EventEnvelope<JsonNode> envelope, DeliveryContext context) throws Exception;
    }

    private final StreamContext streamContext;
    private final EventCodec codec;
    private final EventHandler handler;
    private final ConsumerSettings settings;
    private final DeadLetterPublisher deadLetters;
    private final ScheduledExecutorService heartbeats;
    private final List<MessageConsumer> bindings = new ArrayList<>();

    private volatile boolean running;

    public DurableEventConsumer(
            StreamContext streamContext,
            JetStream jetStream,
            EventCodec codec,
            EventHandler handler,
            ConsumerSettings settings) {

        this.streamContext = streamContext;
        this.codec = codec;
        this.handler = handler;
        this.settings = settings;
        this.deadLetters = new DeadLetterPublisher(jetStream, settings.durableName());
        this.heartbeats =
                Executors.newScheduledThreadPool(
                        1, Thread.ofVirtual().name("event-heartbeat-", 0).factory());
    }

    /** Reconciles the durable consumer and starts consuming. */
    public void start() {
        try {
            streamContext.createOrUpdateConsumer(consumerConfiguration());

            ConsumeOptions consumeOptions =
                    ConsumeOptions.builder().batchSize(settings.batchSize()).build();

            // Each binding pulls independently from the same durable consumer, which is how a
            // single process scales beyond one in-flight message.
            for (int i = 0; i < settings.concurrency(); i++) {
                bindings.add(
                        streamContext
                                .getConsumerContext(settings.durableName())
                                .consume(consumeOptions, this::onMessage));
            }

            running = true;
            log.info(
                    "Consuming '{}' from stream '{}' on subject '{}' ({} binding(s), max {} deliveries)",
                    settings.durableName(),
                    settings.streamName(),
                    settings.filterSubject(),
                    settings.concurrency(),
                    settings.maxDeliver());
        } catch (IOException | JetStreamApiException e) {
            throw new IllegalStateException(
                    "Failed to start the durable consumer '%s' on stream '%s'"
                            .formatted(settings.durableName(), settings.streamName()),
                    e);
        }
    }

    private ConsumerConfiguration consumerConfiguration() {
        return ConsumerConfiguration.builder()
                .durable(settings.durableName())
                .filterSubject(settings.filterSubject())
                // Explicit acknowledgement is the basis of at-least-once delivery: a
                // message leaves the pending set only once the handler has committed.
                .ackPolicy(AckPolicy.Explicit)
                .deliverPolicy(DeliverPolicy.All)
                .replayPolicy(ReplayPolicy.Instant)
                .ackWait(settings.ackWait())
                .maxDeliver(settings.maxDeliver())
                // Consumer-side back-pressure.
                .maxAckPending(settings.maxAckPending())
                // One delay per redelivery, so a struggling dependency is retried with
                // increasing patience rather than hammered.
                .backoff(settings.backoffLadder())
                .build();
    }

    private void onMessage(Message message) {
        EventEnvelope<JsonNode> envelope;

        // Phase 1: parse the envelope. A body that cannot be understood can never
        // succeed, so it is dead-lettered without consulting the handler.
        try {
            envelope = codec.readEnvelope(message.getData());
        } catch (PermanentEventException e) {
            settlePermanently(message, e.reason(), e);
            return;
        }

        MDC.put(MDC_CORRELATION_ID, envelope.correlationId());
        MDC.put(MDC_EVENT_ID, envelope.id().toString());
        ScheduledFuture<?> heartbeat = startHeartbeat(message);

        long attempt = message.metaData().deliveredCount();
        DeliveryContext context =
                new DeliveryContext(attempt, settings.maxDeliver(), attempt >= settings.maxDeliver());

        try {
            handler.handle(envelope, context);
            message.ack();
            log.info("Processed event {} ({})", envelope.id(), envelope.type());

        } catch (PermanentEventException e) {
            settlePermanently(message, e.reason(), e);

        } catch (Exception e) {
            // Final attempt: JetStream would stop redelivering after this one, so the
            // message must be captured now or it disappears.
            if (context.lastAttempt()) {
                settlePermanently(message, "max-deliveries-exceeded", e);
                return;
            }

            Duration retryAfter = e instanceof TransientEventException t ? t.retryAfter() : null;
            if (retryAfter == null) {
                // Defer to the backoff ladder configured on the durable consumer.
                message.nak();
            } else {
                message.nakWithDelay(retryAfter);
            }

            log.warn(
                    "Event {} ({}) failed on delivery {}/{}; requesting redelivery",
                    envelope.id(),
                    envelope.type(),
                    attempt,
                    settings.maxDeliver(),
                    e);
        } finally {
            heartbeat.cancel(false);
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_EVENT_ID);
        }
    }

    /** Terminates the message and preserves it in the dead-letter stream. */
    private void settlePermanently(Message message, String reason, Throwable cause) {
        try {
            deadLetters.publish(message, reason, cause);
            message.term();
        } catch (TransientEventException e) {
            // The dead letter could not be stored. Leave the message unacknowledged so
            // it comes back rather than vanishing without a trace.
            message.nakWithDelay(Duration.ofSeconds(5));
        }
    }

    /**
     * Periodically tells the broker the handler is still working, which extends the
     * acknowledgement deadline.
     */
    private ScheduledFuture<?> startHeartbeat(Message message) {
        long intervalMillis = Math.max(1_000L, settings.ackWait().toMillis() / 3);

        return heartbeats.scheduleAtFixedRate(
                () -> {
                    try {
                        message.inProgress();
                    } catch (RuntimeException e) {
                        log.debug("Could not extend the acknowledgement deadline", e);
                    }
                },
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() {
        return running;
    }

    /** Stops accepting new messages and lets in-flight handlers finish. */
    @Override
    public void close() {
        running = false;
        log.info("Stopping consumer '{}'", settings.durableName());

        bindings.forEach(MessageConsumer::stop);
        heartbeats.shutdown();

        try {
            if (!heartbeats.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeats.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            heartbeats.shutdownNow();
        }

        log.info("Consumer '{}' stopped", settings.durableName());
    }

    /** Tuning for a durable consumer. */
    public record ConsumerSettings(
            String streamName,
            String durableName,
            String filterSubject,
            Duration ackWait,
            long maxDeliver,
            long maxAckPending,
            int batchSize,
            int concurrency,
            Duration backoffBase,
            Duration backoffMax) {

        /**
         * Builds the redelivery delay ladder: one entry per retry, doubling each time and
         * capped at #backoffMax().
         */
        public Duration[] backoffLadder() {
            int steps = (int) Math.max(1, maxDeliver - 1);
            Duration[] ladder = new Duration[steps];

            long base = backoffBase().toMillis();
            long cap = backoffMax().toMillis();

            for (int i = 0; i < steps; i++) {
                long delay = Math.min(cap, base * (1L << Math.min(i, 20)));
                ladder[i] = Duration.ofMillis(delay);
            }

            return ladder;
        }
    }
}
