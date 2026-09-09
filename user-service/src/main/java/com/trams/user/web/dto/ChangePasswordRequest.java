package com.trams.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Password change payload.
 *
 * <p>The current password is required even though the caller is already authenticated:
 * proof of knowledge is what prevents a stolen access token from being escalated into
 * permanent account takeover.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "must not be blank") @Size(max = 128) String currentPassword,
        @NotBlank(message = "must not be blank")
                @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
                String newPassword) {}
