package com.idraak.smartbite.v2.profile;

import java.util.Map;
import java.util.Optional;

/**
 * The profile documents the app wrote straight to Firestore ({@code users/{uid}}) before D-024. Read once per
 * user, on that user's first profile read from our database, and deleted only on account deletion. Nothing else
 * changes or removes Firestore data.
 */
public interface LegacyProfileSource {

    /** The raw Firestore document, or empty if the user has none. @throws LegacyUnavailableException if unreachable */
    Optional<Map<String, Object>> fetch(String uid);

    /** Account deletion only. @return true if a document was deleted */
    boolean delete(String uid);

    class LegacyUnavailableException extends RuntimeException {
        public LegacyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** No Firebase (tests and local runs): there is never a legacy profile. */
    LegacyProfileSource NONE = new LegacyProfileSource() {
        @Override
        public Optional<Map<String, Object>> fetch(String uid) {
            return Optional.empty();
        }

        @Override
        public boolean delete(String uid) {
            return false;
        }
    };
}
