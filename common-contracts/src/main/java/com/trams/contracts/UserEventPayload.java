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

/**
 * Payload of a user domain event.
 *
 * <p>The interface is {@code sealed}, which is the point: a consumer can {@code switch}
 * over the permitted subtypes and the compiler will reject the switch if a new event type
 * is added without handling it. Adding an event therefore becomes a compile-time
 * conversation with every consumer rather than a runtime surprise.
 *
 * <p>Every payload carries the recipient's identity, so a consumer never has to call back
 * into the User Service to render a notification — which is what keeps the two services
 * genuinely decoupled rather than merely asynchronous.
 */
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

    /** Profile attributes changed; {@code changedFields} allows a specific message. */
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

    /**
     * The password was changed. This drives a security alert, so the originating address
     * is included when the change came from an authenticated session.
     */
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
