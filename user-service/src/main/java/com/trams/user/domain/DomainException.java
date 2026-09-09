package com.trams.user.domain;

/** Base type for expected, business-rule failures. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Stable, machine-readable code that clients may branch on. */
    public abstract String errorCode();
}
