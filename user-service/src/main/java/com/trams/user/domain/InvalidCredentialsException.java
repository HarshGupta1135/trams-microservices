package com.trams.user.domain;

/**
 * Raised for both an unknown email and a wrong password.
 *
 * <p>One indistinguishable failure for both cases is deliberate: separate responses would
 * let an attacker enumerate which addresses have accounts before attempting any password.
 */
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("The email address or password is incorrect.");
    }

    @Override
    public String errorCode() {
        return "INVALID_CREDENTIALS";
    }
}
