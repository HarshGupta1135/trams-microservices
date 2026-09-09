package com.trams.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trams.contracts.EventEnvelope;
import com.trams.contracts.UserEventPayload;
import com.trams.contracts.UserEventType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Tests the serialisation boundary between the two services. */
class EventCodecTest {

    private static ObjectMapper objectMapper;
    private static Validator validator;
    private static EventCodec codec;

    @BeforeAll
    static void setUp() {
        objectMapper = JsonMapper.builder().build();
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        codec = new EventCodec(objectMapper, validator);
    }

    private static EventEnvelope<UserEventPayload> registeredEvent() {
        UserEventPayload payload =
                new UserEventPayload.UserRegistered(
                        UUID.randomUUID(),
                        "someone@example.com",
                        "Someone Example",
                        Set.of("USER"),
                        Instant.parse("2026-02-03T04:05:06Z"));

        return EventEnvelope.of(UserEventType.REGISTERED, payload, "corr-1234", Instant.parse("2026-02-03T04:05:06Z"));
    }

    @Test
    @DisplayName("an envelope survives a serialise/deserialise round trip intact")
    void roundTripsAnEnvelope() {
        EventEnvelope<UserEventPayload> original = registeredEvent();

        byte[] wire = codec.serialise(original);
        EventEnvelope<JsonNode> parsed = codec.readEnvelope(wire);

        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.type()).isEqualTo(UserEventType.REGISTERED.wireName());
        assertThat(parsed.subject()).isEqualTo(UserEventType.REGISTERED.subject());
        assertThat(parsed.correlationId()).isEqualTo("corr-1234");
        assertThat(parsed.dataVersion()).isEqualTo(UserEventType.REGISTERED.dataVersion());
        // Instants must survive as instants, not drift through a local time zone.
        assertThat(parsed.occurredAt()).isEqualTo(original.occurredAt());

        UserEventPayload.UserRegistered payload =
                (UserEventPayload.UserRegistered)
                        codec.readPayload(parsed, UserEventPayload.UserRegistered.class);

        assertThat(payload).isEqualTo(original.data());
    }

    @Test
    @DisplayName("a body that is not JSON is a permanent failure, not a retryable one")
    void rejectsNonJsonBody() {
        // Retrying this would waste the delivery budget: the bytes will never parse.
        assertThatThrownBy(() -> codec.readEnvelope("this is not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MalformedEventException.class)
                .isInstanceOf(PermanentEventException.class);
    }

    @Test
    @DisplayName("an envelope missing required metadata is rejected permanently")
    void rejectsIncompleteEnvelope() {
        String incomplete = "{\"id\":\"" + UUID.randomUUID() + "\"}";

        assertThatThrownBy(() -> codec.readEnvelope(incomplete.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PermanentEventException.class);
    }

    @Test
    @DisplayName("an envelope from an incompatible future spec version is rejected permanently")
    void rejectsUnknownSpecVersion() {
        EventEnvelope<UserEventPayload> original = registeredEvent();
        String json = new String(codec.serialise(original), StandardCharsets.UTF_8)
                .replace("\"specVersion\":1", "\"specVersion\":99");

        assertThatThrownBy(() -> codec.readEnvelope(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("specVersion");
    }

    @Test
    @DisplayName("a payload violating the contract is rejected rather than half-processed")
    void rejectsPayloadViolatingTheContract() {
        // A producer bug: a registered user with no email address.
        EventEnvelope<UserEventPayload> original = registeredEvent();
        String json = new String(codec.serialise(original), StandardCharsets.UTF_8)
                .replace("\"someone@example.com\"", "\"\"");

        EventEnvelope<JsonNode> parsed = codec.readEnvelope(json.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> codec.readPayload(parsed, UserEventPayload.UserRegistered.class))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("unknown payload fields are tolerated, so a producer can add fields safely")
    void toleratesUnknownPayloadFields() {
        // Forward compatibility is what allows the producer to be deployed before the consumer.
        EventEnvelope<UserEventPayload> original = registeredEvent();
        String json = new String(codec.serialise(original), StandardCharsets.UTF_8)
                .replace("\"fullName\":", "\"aFieldFromTheFuture\":\"ignored\",\"fullName\":");

        EventEnvelope<JsonNode> parsed = codec.readEnvelope(json.getBytes(StandardCharsets.UTF_8));
        UserEventPayload payload = codec.readPayload(parsed, UserEventPayload.UserRegistered.class);

        assertThat(payload.email()).isEqualTo("someone@example.com");
        assertThat(payload.fullName()).isEqualTo("Someone Example");
    }

    @Test
    @DisplayName("every registered event type round trips through the codec")
    void roundTripsEveryEventType() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-02-03T04:05:06Z");

        record Case(UserEventType type, UserEventPayload payload) {}

        var cases =
                java.util.List.of(
                        new Case(
                                UserEventType.REGISTERED,
                                new UserEventPayload.UserRegistered(
                                        userId, "a@example.com", "A", Set.of("USER"), now)),
                        new Case(
                                UserEventType.PROFILE_UPDATED,
                                new UserEventPayload.UserProfileUpdated(
                                        userId, "a@example.com", "A", java.util.List.of("fullName"), now)),
                        new Case(
                                UserEventType.PASSWORD_CHANGED,
                                new UserEventPayload.UserPasswordChanged(
                                        userId, "a@example.com", "A", "203.0.113.7", now)),
                        new Case(
                                UserEventType.DELETED,
                                new UserEventPayload.UserDeleted(userId, "a@example.com", "A", now)));

        for (Case testCase : cases) {
            EventEnvelope<UserEventPayload> envelope =
                    EventEnvelope.of(testCase.type(), testCase.payload(), "corr", now);

            EventEnvelope<JsonNode> parsed = codec.readEnvelope(codec.serialise(envelope));
            UserEventPayload payload = codec.readPayload(parsed, testCase.type().payloadType());

            assertThat(payload)
                    .as("round trip for %s", testCase.type())
                    .isEqualTo(testCase.payload());
        }
    }
}
