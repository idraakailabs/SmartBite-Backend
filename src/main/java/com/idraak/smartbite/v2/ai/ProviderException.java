package com.idraak.smartbite.v2.ai;

public class ProviderException extends RuntimeException {

    public enum Kind { RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, TIMEOUT, CONNECTION }

    private final Kind kind;

    public ProviderException(Kind kind, String message, Throwable cause) {
        super(message, cause, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
