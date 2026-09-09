package com.trams.contracts;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The registry binding each user event to its wire name, subject, payload type and schema
 * version.
 *
 * <p>This is the single source of truth for both sides of the integration: the producer
 * derives the subject it publishes to from this enum, and the consumer resolves the
 * concrete payload class from it. There is no second place where a subject name is
 * spelled out, so the two can never drift apart.
 */
public enum UserEventType {
    REGISTERED("user.registered", "registered", 1, UserEventPayload.UserRegistered.class),
    PROFILE_UPDATED("user.profile_updated", "profile_updated", 1, UserEventPayload.UserProfileUpdated.class),
    PASSWORD_CHANGED("user.password_changed", "password_changed", 1, UserEventPayload.UserPasswordChanged.class),
    DELETED("user.deleted", "deleted", 1, UserEventPayload.UserDeleted.class);

    private static final Map<String, UserEventType> BY_WIRE_NAME =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(UserEventType::wireName, Function.identity()));

    private final String wireName;
    private final String subject;
    private final int dataVersion;
    private final Class<? extends UserEventPayload> payloadType;

    UserEventType(String wireName, String fact, int dataVersion, Class<? extends UserEventPayload> payloadType) {
        this.wireName = wireName;
        this.subject = Subjects.userEvent(fact);
        this.dataVersion = dataVersion;
        this.payloadType = payloadType;
    }

    /** The value carried in {@link EventEnvelope#type()}. */
    public String wireName() {
        return wireName;
    }

    /** The NATS subject this event is published to. */
    public String subject() {
        return subject;
    }

    public int dataVersion() {
        return dataVersion;
    }

    public Class<? extends UserEventPayload> payloadType() {
        return payloadType;
    }

    /**
     * Resolves a wire name, returning empty for an unrecognised type.
     *
     * <p>Deliberately not throwing: a consumer receiving an unknown event type is an
     * expected condition during a rolling deploy where the producer is already emitting a
     * newer event. The caller decides whether that is a dead letter or simply ignorable.
     */
    public static Optional<UserEventType> fromWireName(String wireName) {
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }
}
