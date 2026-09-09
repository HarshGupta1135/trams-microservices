package com.trams.user.web.dto;

import com.trams.user.domain.Role;
import com.trams.user.domain.User;
import com.trams.user.domain.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public representation of a user.
 *
 * <p>A dedicated response type rather than serialising the entity directly. That is not
 * ceremony: exposing the entity would leak {@code passwordHash} the moment someone adds a
 * getter, and would couple the API's wire format to the database schema so that a column
 * rename becomes a breaking API change.
 */
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        UserStatus status,
        List<String> roles,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus(),
                user.getRoles().stream().map(Role::name).sorted().toList(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
