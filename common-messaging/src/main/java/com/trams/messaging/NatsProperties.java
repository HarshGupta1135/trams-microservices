package com.trams.messaging;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Broker configuration, bound from the environment under trams.nats.*. */
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
        // Outbound messages are buffered while the connection is down and flushed on reconnect.
        @DefaultValue("8MB") DataSize reconnectBufferSize,
        @DefaultValue @NotNull Stream stream) {

    /** Transport security. */
    public record Tls(@DefaultValue("true") boolean enabled, String caFile) {}

    /** Stream-level durability settings. */
    public record Stream(
            @DefaultValue("1") @Min(1) int replicas,
            @DefaultValue("30d") Duration maxAge,
            @DefaultValue("5m") Duration duplicateWindow) {}
}
