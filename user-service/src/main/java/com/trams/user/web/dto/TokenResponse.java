package com.trams.user.web.dto;

import com.trams.user.application.AuthenticationService.AuthenticationResult;
import java.time.Instant;

/**
 * Token pair returned by login and refresh.
 *
 * <p>Field names follow OAuth 2 conventions ({@code tokenType}, {@code expiresIn}) so the
 * response is familiar to any client library, and both a relative and an absolute expiry
 * are provided: the former is what OAuth clients expect, the latter lets a client refresh
 * proactively without depending on its own clock being in sync at the moment of receipt.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Instant expiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UserResponse user) {

    private static final String BEARER = "Bearer";

    public static TokenResponse from(AuthenticationResult result) {
        return new TokenResponse(
                result.accessToken(),
                BEARER,
                result.expiresInSeconds(),
                result.accessTokenExpiresAt(),
                result.refreshToken(),
                result.refreshTokenExpiresAt(),
                UserResponse.from(result.user()));
    }
}
