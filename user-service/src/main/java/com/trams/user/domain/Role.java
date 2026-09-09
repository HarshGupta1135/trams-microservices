package com.trams.user.domain;

/** Authorities a user may hold. */
public enum Role {
    USER,
    ADMIN;

    /** The authority name Spring Security expects, i.e. ROLE_ADMIN. */
    public String authority() {
        return "ROLE_" + name();
    }
}
