package com.trams.user.domain;

/** Raised when a registration or profile update would duplicate an existing address. */
public class EmailAlreadyRegisteredException extends DomainException {

    public EmailAlreadyRegisteredException() {
        // The message is intentionally generic. Confirming which addresses are registered
        // turns this endpoint into an account-enumeration oracle.
        super("That email address cannot be used.");
    }

    @Override
    public String errorCode() {
        return "EMAIL_ALREADY_REGISTERED";
    }
}
