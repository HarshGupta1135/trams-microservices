package com.trams.messaging;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Converts between event envelopes and the bytes on the wire. */
public class EventCodec {

    private static final TypeReference<EventEnvelope<JsonNode>> RAW_ENVELOPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public EventCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public byte[] serialise(EventEnvelope<? extends UserEventPayload> envelope) {
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Failed to serialise event " + envelope.id() + " of type " + envelope.type(), e);
        }
    }

    /** Reads the envelope, leaving the payload unparsed. */
    public EventEnvelope<JsonNode> readEnvelope(byte[] payload) {
        EventEnvelope<JsonNode> envelope;
        try {
            envelope = objectMapper.readValue(payload, RAW_ENVELOPE);
        } catch (JacksonException e) {
            throw new MalformedEventException("Message body is not a valid event envelope: " + e.getOriginalMessage(), e);
        }

        if (envelope.specVersion() != EventEnvelope.CURRENT_SPEC_VERSION) {
            throw new PermanentEventException(
                    "Unsupported envelope specVersion %d (this consumer understands %d)"
                            .formatted(envelope.specVersion(), EventEnvelope.CURRENT_SPEC_VERSION),
                    "unsupported-spec-version");
        }

        return envelope;
    }

    /** Binds and validates the payload of an already-parsed envelope. */
    public <T extends UserEventPayload> T readPayload(EventEnvelope<JsonNode> envelope, Class<T> payloadType) {
        T payload;
        try {
            payload = objectMapper.treeToValue(envelope.data(), payloadType);
        } catch (JacksonException e) {
            throw new MalformedEventException(
                    "Payload of event %s does not match %s: %s"
                            .formatted(envelope.id(), payloadType.getSimpleName(), e.getOriginalMessage()),
                    e);
        }

        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            String detail =
                    violations.stream()
                            .map(v -> v.getPropertyPath() + " " + v.getMessage())
                            .sorted()
                            .collect(Collectors.joining("; "));

            throw new PermanentEventException(
                    "Payload of event %s violates the event contract: %s".formatted(envelope.id(), detail),
                    "contract-violation");
        }

        return payload;
    }
}
