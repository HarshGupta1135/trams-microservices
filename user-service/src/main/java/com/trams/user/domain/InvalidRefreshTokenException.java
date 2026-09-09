package com.trams.user.domain;

/** Raised when a refresh token is unknown, expired, or already consumed. */
public class InvalidRefreshTokenException extends DomainException {

    public InvalidRefreshTokenException(String reason) {
        super("The refresh token is not valid: " + reason);
    }

    @Override
    public String errorCode() {
        return "INVALID_REFRESH_TOKEN";
    }
}
