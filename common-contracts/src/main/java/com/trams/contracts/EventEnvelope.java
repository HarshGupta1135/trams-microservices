package com.trams.contracts;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral envelope wrapping every domain event, modelled on the CloudEvents
 * specification.
 */
public record EventEnvelope<T>(
        @NotNull UUID id,
        @Positive int specVersion,
        @NotBlank String type,
        @Positive int dataVersion,
        @NotBlank String source,
        @NotBlank String subject,
        @NotNull Instant occurredAt,
        @NotBlank String correlationId,
        @NotNull T data) {

    /** Current envelope version. */
    public static final int CURRENT_SPEC_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(data, "data");
    }

    /**
     * Builds an envelope for a user event, deriving the subject and data version from the event
     * type so a producer cannot accidentally publish to the wrong subject.
     */
    public static <T extends UserEventPayload> EventEnvelope<T> of(
            UserEventType eventType, T payload, String correlationId, Instant occurredAt) {

        return new EventEnvelope<>(
                UUID.randomUUID(),
                CURRENT_SPEC_VERSION,
                eventType.wireName(),
                eventType.dataVersion(),
                Sources.USER_SERVICE,
                eventType.subject(),
                occurredAt,
                correlationId,
                payload);
    }

    /** Replaces the payload while preserving all envelope metadata. */
    public <R> EventEnvelope<R> withData(R replacement) {
        return new EventEnvelope<>(
                id, specVersion, type, dataVersion, source, subject, occurredAt, correlationId, replacement);
    }
}
