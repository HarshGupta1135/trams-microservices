package com.trams.user.domain;

public enum UserStatus {
    /** May authenticate and use the API. */
    ACTIVE,
    /**
     * Retained but barred from authenticating. Disabling rather than deleting preserves
     * referential history (who performed which action) while immediately removing access.
     */
    DISABLED
}
