package com.trams.notification.config;

import com.trams.notification.domain.NotificationChannel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Notification behaviour, bound from trams.notification.*. */
@ConfigurationProperties(prefix = "trams.notification")
@Validated
public record NotificationProperties(
        @DefaultValue("EMAIL") @NotNull NotificationChannel channel,
        @DefaultValue("TRAMS") @NotBlank String applicationName,
        @NotBlank @Email String fromAddress,
        @DefaultValue("TRAMS") String fromName,
        // Base URL used to build links inside notification bodies.
        @DefaultValue("http://localhost:8080") String appUrl,
        @DefaultValue @NotNull Consumer consumer) {

    /** Durable consumer tuning. */
    public record Consumer(
            @DefaultValue("60s") Duration ackWait,
            @DefaultValue("5") @Min(1) long maxDeliver,
            @DefaultValue("256") @Min(1) long maxAckPending,
            @DefaultValue("25") @Min(1) int batchSize,
            @DefaultValue("4") @Min(1) int concurrency,
            @DefaultValue("2s") Duration backoffBase,
            @DefaultValue("60s") Duration backoffMax,
            @DefaultValue("2m") Duration streamWaitTimeout) {}
}
