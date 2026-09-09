package com.trams.user.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Partial profile update: a null field means "leave unchanged".
 *
 * <p>Both fields are optional, so a request that sets neither is valid and simply changes
 * nothing - which the service detects and handles without emitting an event.
 */
public record UpdateProfileRequest(
        @Size(min = 1, max = 200, message = "must be between 1 and 200 characters") String fullName,
        @Email(message = "must be a well-formed email address") @Size(max = 320) String email) {}
