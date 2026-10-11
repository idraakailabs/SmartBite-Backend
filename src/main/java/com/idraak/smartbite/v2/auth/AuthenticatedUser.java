package com.idraak.smartbite.v2.auth;

import java.time.Instant;

/** The verified caller of a v2 request. Only the uid flows downstream. */
public record AuthenticatedUser(String uid, String signInProvider, Instant authTime, String rawToken) {

    public static final String ATTRIBUTE = AuthenticatedUser.class.getName();

    @Override
    public String toString() {
        return "AuthenticatedUser[uid=<redacted>, provider=" + signInProvider + "]";
    }
}
