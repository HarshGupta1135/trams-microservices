package com.trams.user.domain;

public enum UserStatus {
    /** May authenticate and use the API. */
    ACTIVE,
    /** Retained but barred from authenticating. */
    DISABLED
}
