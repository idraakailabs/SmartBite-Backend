package com.idraak.smartbite.v2.auth;

public class TokenVerificationException extends RuntimeException {

    public enum Kind { INVALID, REVOKED, UNAVAILABLE }

    private final Kind kind;

    public TokenVerificationException(Kind kind, Throwable cause) {
        super(kind.name(), cause, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
