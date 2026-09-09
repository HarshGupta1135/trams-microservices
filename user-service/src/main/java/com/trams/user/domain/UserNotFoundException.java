package com.trams.user.domain;

import java.util.UUID;

public class UserNotFoundException extends DomainException {

    public UserNotFoundException(UUID userId) {
        super("No user exists with id " + userId);
    }

    @Override
    public String errorCode() {
        return "USER_NOT_FOUND";
    }
}
