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
 *
 * <p>Metadata is kept strictly separate from the domain payload ({@code data}) so that
 * infrastructure concerns — deduplication, tracing, schema evolution — never require a
 * consumer to understand the payload, and a payload change never disturbs the plumbing.
 *
 * @param id          unique event identifier; also used as the broker deduplication key
 * @param specVersion version of this envelope structure
 * @param type        domain event name, e.g. {@code user.registered}
 * @param dataVersion schema version of {@code data}, incremented on breaking changes
 * @param source      service that emitted the event
 * @param subject     broker subject the event was published to
 * @param occurredAt  when the fact occurred in the producer's domain, not when it was sent
 * @param correlationId ties the event back to the originating HTTP request
 * @param data        the domain payload
 * @param <T>         payload type
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

    /**
     * Current envelope version. Bumping this is a breaking change for every consumer, so
     * routine payload evolution uses the per-event {@link #dataVersion} instead.
     */
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
     * Builds an envelope for a user event, deriving the subject and data version from the
     * event type so a producer cannot accidentally publish to the wrong subject.
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
