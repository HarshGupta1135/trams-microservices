package com.trams.contracts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Payload of a user domain event. */
public sealed interface UserEventPayload
        permits UserEventPayload.UserRegistered,
                UserEventPayload.UserProfileUpdated,
                UserEventPayload.UserPasswordChanged,
                UserEventPayload.UserDeleted {

    UUID userId();

    String email();

    String fullName();

    /** The event type that carries this payload. */
    UserEventType eventType();

    /** A new account was created. */
    record UserRegistered(
            @NotNull UUID userId,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotNull Set<String> roles,
            @NotNull Instant registeredAt)
            implements UserEventPayload {

        @Override
        public UserEventType eventType() {
            return UserEventType.REGISTERED;
        }
    }

    /** Profile attributes changed; changedFields allows a specific message. */
    record UserProfileUpdated(
            @NotNull UUID userId,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotEmpty List<String> changedFields,
            @NotNull Instant updatedAt)
            implements UserEventPayload {

        @Override
        public UserEventType eventType() {
            return UserEventType.PROFILE_UPDATED;
        }
    }

    /** The password was changed. */
    record UserPasswordChanged(
            @NotNull UUID userId,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @Size(max = 64) String requestIp,
            @NotNull Instant changedAt)
            implements UserEventPayload {

        @Override
        public UserEventType eventType() {
            return UserEventType.PASSWORD_CHANGED;
        }
    }

    /** The account was deleted. */
    record UserDeleted(
            @NotNull UUID userId,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotNull Instant deletedAt)
            implements UserEventPayload {

        @Override
        public UserEventType eventType() {
            return UserEventType.DELETED;
        }
    }
}
