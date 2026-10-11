package com.idraak.smartbite.v2.auth;

/** Verifies a Firebase ID token. Implementations: Admin SDK (production) and a fake (test/local only). */
public interface IdTokenVerifier {

    /**
     * @param checkRevoked also check revocation (network call); used for account deletion only
     * @throws TokenVerificationException when the token is invalid, revoked, or verification is unavailable
     */
    AuthenticatedUser verify(String token, boolean checkRevoked);
}
