package com.idraak.smartbite.v2.profile;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.web.UidHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The user's profile in our own database (D-024, BE-022). The profile is the same JSON object the app keeps
 * locally, stored as-is. The first read for a user without a row imports their legacy Firestore document
 * ({@code users/{uid}}); Firestore is never changed except on account deletion.
 */
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    static final String FIRESTORE_TOKEN_FIELD = "fcmToken";

    private final ProfileRepository repo;
    private final LegacyProfileSource legacy;
    private final ObjectMapper mapper;
    private final V2Properties.History cfg;
    private final UidHasher hasher;

    /** @param repo null when no database is configured */
    public ProfileService(ProfileRepository repo, LegacyProfileSource legacy, ObjectMapper mapper, V2Properties.History cfg,
                          UidHasher hasher) {
        this.repo = repo;
        this.legacy = legacy;
        this.mapper = mapper;
        this.cfg = cfg;
        this.hasher = hasher;
    }

    /** @throws ApiException NOT_FOUND when the user has no profile anywhere */
    public ProfileRepository.Stored get(String uid) {
        ProfileRepository r = require();
        Optional<ProfileRepository.Stored> stored = db(() -> r.find(uid));
        if (stored.isPresent() && stored.get().profile() != null) return stored.get();
        Optional<Map<String, Object>> doc;
        try {
            doc = legacy.fetch(uid);
        } catch (LegacyProfileSource.LegacyUnavailableException e) {
            // Unknown whether a legacy profile exists: the app treats this like being offline and keeps its copy.
            throw ApiException.providerUnavailable(10, e);
        }
        if (doc.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND);
        Object token = doc.get().get(FIRESTORE_TOKEN_FIELD);
        ProfileRepository.Stored imported = db(() -> r.importIfAbsent(uid, fromFirestore(doc.get()),
                token instanceof String s && !s.isBlank() ? s : null));
        log.info("profile imported from firestore uid_hash={}", hasher.hash(uid));
        return imported;
    }

    public ProfileRepository.Stored put(String uid, JsonNode profile) {
        ProfileRepository r = require();
        if (profile == null || !profile.isObject()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "profile must be an object");
        ObjectNode copy = ((ObjectNode) profile).deepCopy();
        copy.remove(FIRESTORE_TOKEN_FIELD);
        if (mapper.writeValueAsString(copy).getBytes(StandardCharsets.UTF_8).length > cfg.maxProfileBytes()) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "profile is too large");
        }
        return db(() -> r.merge(uid, copy, "app"));
    }

    public void setDeviceToken(String uid, String token) {
        ProfileRepository r = require();
        db(() -> {
            r.setFcmToken(uid, token.strip());
            return null;
        });
    }

    /** Account deletion: our row and the legacy Firestore document. @return "deleted", "none" or "not_configured" */
    public String deleteUser(String uid) {
        boolean any = false;
        if (repo != null) any = db(() -> repo.delete(uid));
        try {
            any |= legacy.delete(uid);
        } catch (LegacyProfileSource.LegacyUnavailableException e) {
            throw ApiException.providerUnavailable(10, e);
        }
        if (repo == null && !any) return "not_configured";
        return any ? "deleted" : "none";
    }

    /**
     * Firestore → the app's JSON: the token moves to its own column, and Firestore's bean naming of the Kotlin
     * property {@code isOnboardingComplete} ({@code onboardingComplete}) is mapped back to the name the app uses.
     */
    JsonNode fromFirestore(Map<String, Object> doc) {
        Map<String, Object> copy = new HashMap<>(doc);
        copy.remove(FIRESTORE_TOKEN_FIELD);
        if (copy.containsKey("onboardingComplete") && !copy.containsKey("isOnboardingComplete")) {
            copy.put("isOnboardingComplete", copy.remove("onboardingComplete"));
        }
        // Firestore values are plain JSON types here (strings, numbers, booleans, lists, maps).
        copy.replaceAll((k, v) -> v instanceof com.google.cloud.Timestamp t ? t.toString() : v);
        return mapper.valueToTree(copy);
    }

    private ProfileRepository require() {
        if (repo == null) throw new ApiException(ErrorCode.FEATURE_UNAVAILABLE);
        return repo;
    }

    private static <T> T db(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw ApiException.providerUnavailable(10, e);
        }
    }
}
