package com.trams.messaging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Broker configuration, bound from the environment under {@code trams.nats.*}.
 *
 * <p>Every value that is a secret ({@code username}, {@code password}) is supplied from
 * the environment and has no default, so the service fails fast at startup rather than
 * silently falling back to a well-known credential.
 *
 * @param url broker URL, e.g. {@code tls://nats:4222}
 * @param username service-specific NATS account, scoped by subject ACLs on the server
 * @param connectionName identifies this client in {@code nats server report connections}
 */
@ConfigurationProperties(prefix = "trams.nats")
@Validated
public record NatsProperties(
        @NotBlank String url,
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String connectionName,
        @DefaultValue @NotNull Tls tls,
        @DefaultValue("10s") Duration connectionTimeout,
        @DefaultValue("2s") Duration reconnectWait,
        // -1 retries forever. A long-lived service should never give up on its broker:
        // exiting would only trade a recoverable outage for a crash loop.
        @DefaultValue("-1") int maxReconnects,
        @DefaultValue("20s") Duration pingInterval,
        // Outbound messages are buffered while the connection is down and flushed on
        // reconnect. Combined with the transactional outbox this means a brief broker
        // outage costs latency, not data.
        @DefaultValue("8MB") DataSize reconnectBufferSize,
        @DefaultValue @NotNull Stream stream) {

    /**
     * Transport security.
     *
     * <p>Only the CA is configured: clients verify the broker's certificate but present
     * none of their own, authenticating with the credentials above over an encrypted
     * channel. Mutual TLS is the natural next step and is discussed in
     * {@code docs/ARCHITECTURE.md}.
     */
    public record Tls(@DefaultValue("true") boolean enabled, String caFile) {}

    /**
     * Stream-level durability settings.
     *
     * @param replicas raise to 3 or 5 on a NATS cluster so a node loss cannot lose events
     * @param maxAge how long events are retained and remain replayable
     * @param duplicateWindow window in which the broker discards a repeated message id;
     *     must comfortably exceed the outbox relay interval so that a producer crash
     *     between "published" and "marked published" cannot duplicate an event
     */
    public record Stream(
            @DefaultValue("1") @Min(1) int replicas,
            @DefaultValue("30d") Duration maxAge,
            @DefaultValue("5m") Duration duplicateWindow) {}
}
