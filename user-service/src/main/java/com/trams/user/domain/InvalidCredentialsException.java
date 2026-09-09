package com.trams.user.domain;

/** Raised for both an unknown email and a wrong password. */
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("The email address or password is incorrect.");
    }

    @Override
    public String errorCode() {
        return "INVALID_CREDENTIALS";
    }
}
