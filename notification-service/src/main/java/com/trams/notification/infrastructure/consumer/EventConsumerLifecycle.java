package com.trams.notification.infrastructure.consumer;

import com.trams.contracts.Subjects;
import com.trams.messaging.DurableEventConsumer;
import com.trams.messaging.EventCodec;
import com.trams.messaging.JetStreamTopology;
import com.trams.notification.application.UserEventHandler;
import com.trams.notification.config.NotificationProperties;
import io.nats.client.JetStream;
import io.nats.client.StreamContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Starts and stops the durable event consumer with the application. */
@Component
public class EventConsumerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerLifecycle.class);

    private final JetStreamTopology topology;
    private final JetStream jetStream;
    private final EventCodec codec;
    private final UserEventHandler handler;
    private final NotificationProperties properties;

    private volatile DurableEventConsumer consumer;

    public EventConsumerLifecycle(
            JetStreamTopology topology,
            JetStream jetStream,
            EventCodec codec,
            UserEventHandler handler,
            NotificationProperties properties) {

        this.topology = topology;
        this.jetStream = jetStream;
        this.codec = codec;
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void start() {
        // This service owns the dead-letter stream, matching its NATS permissions: it may
        // create DEAD_LETTER and publish to dlq.>, but has no rights to create or modify.
        topology.ensureStream(topology.deadLetterStream());

        StreamContext streamContext = awaitProducerStream();

        NotificationProperties.Consumer settings = properties.consumer();

        consumer =
                new DurableEventConsumer(
                        streamContext,
                        jetStream,
                        codec,
                        handler::handle,
                        new DurableEventConsumer.ConsumerSettings(
                                Subjects.STREAM_USER_EVENTS,
                                Subjects.NOTIFICATION_CONSUMER,
                                Subjects.USER_EVENTS_WILDCARD,
                                settings.ackWait(),
                                settings.maxDeliver(),
                                settings.maxAckPending(),
                                settings.batchSize(),
                                settings.concurrency(),
                                settings.backoffBase(),
                                settings.backoffMax()));

        consumer.start();
    }

    /** Waits for the User Service to create the stream this service consumes. */
    private StreamContext awaitProducerStream() {
        try {
            return topology.awaitStream(
                    Subjects.STREAM_USER_EVENTS, properties.consumer().streamWaitTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the event stream", e);
        }
    }

    @Override
    public void stop() {
        DurableEventConsumer running = this.consumer;

        if (running != null) {
            running.close();
            this.consumer = null;
        }

        log.info("Event consumer lifecycle stopped");
    }

    @Override
    public boolean isRunning() {
        DurableEventConsumer running = this.consumer;
        return running != null && running.isRunning();
    }

    /**
     * Stops early in the shutdown sequence (a high phase stops first), so events drain while
     * the database and mail sender are still usable.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
