package com.trams.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the published event contract.
 *
 * <p>These assertions look almost tautological, and that is the point: the registry is the
 * single place where a subject name, a wire name and a payload class are bound together,
 * so a careless edit here would silently break the integration between two independently
 * deployed services. A failing test is far cheaper than a producer publishing to a subject
 * no consumer is listening on.
 */
class UserEventTypeTest {

    @ParameterizedTest
    @EnumSource(UserEventType.class)
    @DisplayName("every event type resolves back from its own wire name")
    void wireNameRoundTrips(UserEventType type) {
        assertThat(UserEventType.fromWireName(type.wireName())).contains(type);
    }

    @ParameterizedTest
    @EnumSource(UserEventType.class)
    @DisplayName("every subject sits under the user-events prefix the NATS ACLs grant")
    void subjectsMatchTheAuthorisedPrefix(UserEventType type) {
        // The producer's NATS credential permits publishing only to `user.events.>`.
        // A subject outside that prefix would be rejected by the broker at runtime.
        assertThat(type.subject()).startsWith(Subjects.USER_EVENTS_PREFIX + ".");
    }

    @ParameterizedTest
    @EnumSource(UserEventType.class)
    @DisplayName("every payload type implements the sealed contract interface")
    void payloadTypesAreContractTypes(UserEventType type) {
        assertThat(UserEventPayload.class).isAssignableFrom(type.payloadType());
    }

    @ParameterizedTest
    @EnumSource(UserEventType.class)
    @DisplayName("every event declares a positive data version")
    void dataVersionsArePositive(UserEventType type) {
        assertThat(type.dataVersion()).isPositive();
    }

    @Test
    @DisplayName("subjects are unique, so no two event types collide on the wire")
    void subjectsAreUnique() {
        var subjects = Arrays.stream(UserEventType.values()).map(UserEventType::subject).collect(Collectors.toSet());

        assertThat(subjects).hasSize(UserEventType.values().length);
    }

    @Test
    @DisplayName("payload types are unique, so a consumer can dispatch unambiguously")
    void payloadTypesAreUnique() {
        var payloadTypes =
                Arrays.stream(UserEventType.values()).map(UserEventType::payloadType).collect(Collectors.toSet());

        assertThat(payloadTypes).hasSize(UserEventType.values().length);
    }

    @Test
    @DisplayName("an unrecognised wire name resolves to empty rather than throwing")
    void unknownWireNameIsEmpty() {
        // A consumer meeting a newer producer's event during a rolling deploy is an
        // expected condition, not an error; the caller decides how to settle it.
        assertThat(UserEventType.fromWireName("user.something_new")).isEmpty();
        assertThat(UserEventType.fromWireName("")).isEmpty();
    }

    @Test
    @DisplayName("each payload reports the event type that carries it")
    void payloadsReportTheirOwnEventType() {
        var registered =
                new UserEventPayload.UserRegistered(
                        java.util.UUID.randomUUID(),
                        "someone@example.com",
                        "Someone",
                        java.util.Set.of("USER"),
                        java.time.Instant.now());

        assertThat(registered.eventType()).isEqualTo(UserEventType.REGISTERED);
        assertThat(registered.eventType().payloadType()).isEqualTo(registered.getClass());
    }
}
