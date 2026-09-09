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

/** Publishing side of the event-driven integration. */
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

    /** Reconciles the stream during bean initialisation. */
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
