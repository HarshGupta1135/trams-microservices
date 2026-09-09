package com.trams.user.domain;

/**
 * Authorities a user may hold.
 *
 * <p>Constrained by a CHECK constraint in the database as well as by this enum, so an
 * unrecognised role cannot be persisted by any route.
 */
public enum Role {
    USER,
    ADMIN;

    /** The authority name Spring Security expects, i.e. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
