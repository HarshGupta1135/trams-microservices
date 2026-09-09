package com.trams.messaging;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the shared messaging infrastructure into any service that puts this module on its
 * classpath.
 */
@AutoConfiguration
@EnableConfigurationProperties(NatsProperties.class)
public class MessagingAutoConfiguration {

    /** Owns the connection lifecycle. */
    @Bean
    @ConditionalOnMissingBean
    public NatsConnectionHolder natsConnectionHolder(NatsProperties properties) {
        return new NatsConnectionHolder(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public Connection natsConnection(NatsConnectionHolder holder) {
        return holder.connection();
    }

    @Bean
    @ConditionalOnMissingBean
    public JetStream jetStream(NatsConnectionHolder holder) {
        return holder.jetStream();
    }

    @Bean
    @ConditionalOnMissingBean
    public JetStreamTopology jetStreamTopology(Connection connection, NatsProperties properties) {
        return new JetStreamTopology(connection, properties);
    }

    /**
     * Uses the application's own ObjectMapper and Validator so events are serialised with the
     * same conventions as the HTTP API (ISO-8601 timestamps.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventCodec eventCodec(ObjectMapper objectMapper, Validator validator) {
        return new EventCodec(objectMapper, validator);
    }

    /** Registered only when the service exposes Actuator health endpoints. */
    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "natsHealthIndicator")
    public NatsHealthIndicator natsHealthIndicator(NatsConnectionHolder holder) {
        return new NatsHealthIndicator(holder);
    }
}
