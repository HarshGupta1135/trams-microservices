package com.trams.contracts;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The registry binding each user event to its wire name, subject, payload type and schema
 * version.
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

    /** The value carried in EventEnvelope#type(). */
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

    /** Resolves a wire name, returning empty for an unrecognised type. */
    public static Optional<UserEventType> fromWireName(String wireName) {
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
    }
}
