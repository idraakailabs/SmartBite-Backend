package com.idraak.smartbite.v2.auth;

import java.time.Clock;
import java.time.Instant;

/**
 * Test and local-development verifier. Accepts {@code test:<uid>}, {@code test:<uid>:<provider>} and
 * {@code test:<uid>:<provider>:<authTimeEpochSeconds>}; {@code test:revoked:...} simulates a revoked token.
 * Refused at startup unless in-memory stores are explicitly allowed (never in staging or production).
 */
public class FakeIdTokenVerifier implements IdTokenVerifier {

    private final Clock clock;

    public FakeIdTokenVerifier(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AuthenticatedUser verify(String token, boolean checkRevoked) {
        if (token == null || !token.startsWith("test:")) {
            throw new TokenVerificationException(TokenVerificationException.Kind.INVALID, null);
        }
        String[] parts = token.split(":");
        if (parts.length < 2 || parts[1].isBlank()) {
            throw new TokenVerificationException(TokenVerificationException.Kind.INVALID, null);
        }
        if ("revoked".equals(parts[1])) {
            if (checkRevoked) throw new TokenVerificationException(TokenVerificationException.Kind.REVOKED, null);
        }
        String provider = parts.length > 2 ? parts[2] : "google.com";
        Instant authTime = parts.length > 3 ? Instant.ofEpochSecond(Long.parseLong(parts[3])) : clock.instant();
        return new AuthenticatedUser(parts[1], provider, authTime, token);
    }
}
