package com.trams.user.domain;

public class AccountDisabledException extends DomainException {

    public AccountDisabledException() {
        super("This account has been disabled.");
    }

    @Override
    public String errorCode() {
        return "ACCOUNT_DISABLED";
    }
}
