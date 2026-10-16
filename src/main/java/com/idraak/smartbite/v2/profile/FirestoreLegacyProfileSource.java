package com.idraak.smartbite.v2.profile;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the app's legacy {@code users/{uid}} documents with the Admin SDK (which bypasses the security rules).
 * The collection is the app's own, so no environment prefix applies: staging reads the same documents, read-only,
 * and copies them into its own database.
 */
public class FirestoreLegacyProfileSource implements LegacyProfileSource {

    static final String USERS = "users";

    private final Firestore db;
    private final long timeoutMs;

    public FirestoreLegacyProfileSource(Firestore db, Duration timeout) {
        this.db = db;
        this.timeoutMs = timeout.toMillis();
    }

    @Override
    public Optional<Map<String, Object>> fetch(String uid) {
        try {
            DocumentSnapshot s = db.collection(USERS).document(uid).get().get(timeoutMs, TimeUnit.MILLISECONDS);
            return s.exists() && s.getData() != null ? Optional.of(s.getData()) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LegacyUnavailableException("legacy profile read interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new LegacyUnavailableException("legacy profile read failed", e);
        }
    }

    @Override
    public boolean delete(String uid) {
        try {
            var ref = db.collection(USERS).document(uid);
            boolean existed = ref.get().get(timeoutMs, TimeUnit.MILLISECONDS).exists();
            if (existed) ref.delete().get(timeoutMs, TimeUnit.MILLISECONDS);
            return existed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LegacyUnavailableException("legacy profile delete interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new LegacyUnavailableException("legacy profile delete failed", e);
        }
    }
}
