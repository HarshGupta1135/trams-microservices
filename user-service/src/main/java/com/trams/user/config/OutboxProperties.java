package com.trams.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Outbox relay tuning, bound from trams.outbox.*. */
@ConfigurationProperties(prefix = "trams.outbox")
public record OutboxProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("1s") Duration baseBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("7d") Duration retention,
        // Per-publish timeout when waiting for the broker's acknowledgement.
        @DefaultValue("5s") Duration publishTimeout,
        // Attempts inside a single publish call before the row is rescheduled.
        @DefaultValue("3") int publishAttempts) {}
