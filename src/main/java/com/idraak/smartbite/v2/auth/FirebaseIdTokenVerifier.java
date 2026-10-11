package com.idraak.smartbite.v2.auth;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

import java.time.Instant;
import java.util.Map;

/** Admin SDK verification: signature (cached Google keys), issuer, audience = project id, expiry. */
public class FirebaseIdTokenVerifier implements IdTokenVerifier {

    private final FirebaseAuth auth;

    public FirebaseIdTokenVerifier(FirebaseAuth auth) {
        this.auth = auth;
    }

    @Override
    public AuthenticatedUser verify(String token, boolean checkRevoked) {
        try {
            FirebaseToken decoded = auth.verifyIdToken(token, checkRevoked);
            Map<String, Object> claims = decoded.getClaims();
            String provider = null;
            if (claims.get("firebase") instanceof Map<?, ?> firebase && firebase.get("sign_in_provider") instanceof String p) {
                provider = p;
            }
            Instant authTime = claims.get("auth_time") instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null;
            return new AuthenticatedUser(decoded.getUid(), provider, authTime, token);
        } catch (FirebaseAuthException e) {
            AuthErrorCode code = e.getAuthErrorCode();
            if (code == AuthErrorCode.REVOKED_ID_TOKEN || code == AuthErrorCode.USER_DISABLED) {
                throw new TokenVerificationException(TokenVerificationException.Kind.REVOKED, e);
            }
            if (code == null) {
                // Not a token problem (e.g. the public keys or the revocation check could not be fetched).
                throw new TokenVerificationException(TokenVerificationException.Kind.UNAVAILABLE, e);
            }
            throw new TokenVerificationException(TokenVerificationException.Kind.INVALID, e);
        } catch (IllegalArgumentException e) {
            throw new TokenVerificationException(TokenVerificationException.Kind.INVALID, e);
        }
    }
}
