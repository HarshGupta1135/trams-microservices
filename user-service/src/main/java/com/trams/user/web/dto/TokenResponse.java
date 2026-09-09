package com.trams.user.web.dto;

import com.trams.user.application.AuthenticationService.AuthenticationResult;
import java.time.Instant;

/** Token pair returned by login and refresh. */
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
