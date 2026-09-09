package com.trams.user.domain;

/**
 * Base type for expected, business-rule failures.
 *
 * <p>Distinguishing these from unexpected errors is what lets the web layer answer a
 * client precisely (409 for a duplicate email) while never leaking the details of a
 * genuine defect, which is reported as a generic 500.
 */
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
