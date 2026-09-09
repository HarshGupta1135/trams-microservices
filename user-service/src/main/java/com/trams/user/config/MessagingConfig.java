package com.trams.user.config;

import com.trams.contracts.Subjects;
import com.trams.messaging.EventCodec;
import com.trams.messaging.EventPublisher;
import com.trams.messaging.JetStreamTopology;
import io.nats.client.JetStream;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Publishing side of the event-driven integration.
 *
 * <p>The User Service owns the {@code USER_EVENTS} stream, matching its NATS permissions:
 * it may create and reconcile that stream and publish to {@code user.events.>}, but has no
 * rights to subscribe to it or to touch any other stream. Ownership of topology sits with
 * the producer because the producer defines what the stream contains.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    @Bean
    public EventPublisher eventPublisher(JetStream jetStream, EventCodec codec, OutboxProperties properties) {
        return new EventPublisher(
                jetStream,
                codec,
                Subjects.STREAM_USER_EVENTS,
                properties.publishTimeout(),
                properties.publishAttempts());
    }

    /**
     * Reconciles the stream during bean initialisation.
     *
     * <p>Deliberately a {@code @PostConstruct} rather than an {@code ApplicationRunner}:
     * runners execute after the context is refreshed, by which point the scheduled outbox
     * relay may already have fired and attempted to publish to a stream that does not yet
     * exist. Doing it at bean-init time removes that race. A failure here aborts startup,
     * which is correct — a producer that cannot guarantee its stream should not accept
     * traffic.
     */
    @Component
    static class UserEventsStreamInitialiser {

        private static final Logger log = LoggerFactory.getLogger(UserEventsStreamInitialiser.class);

        private final JetStreamTopology topology;

        UserEventsStreamInitialiser(JetStreamTopology topology) {
            this.topology = topology;
        }

        @PostConstruct
        void ensureTopology() {
            topology.ensureStream(topology.userEventsStream());
            log.info("JetStream stream '{}' is ready", Subjects.STREAM_USER_EVENTS);
        }
    }
}
