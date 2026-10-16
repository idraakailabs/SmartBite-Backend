package com.idraak.smartbite.v2.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

/** SQL for {@code user_profile} (migration V1). Scoped by the verified uid. */
public class ProfileRepository {

    /** {@code profile} is null for a row that only holds an FCM token. */
    public record Stored(JsonNode profile, Instant updatedAt, String source) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProfileRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Stored> find(String uid) {
        return jdbc.query("select profile::text, updated_at, source from user_profile where uid = ?", rs -> {
            if (!rs.next()) return Optional.<Stored>empty();
            String json = rs.getString(1);
            return Optional.of(new Stored(json == null ? null : mapper.readTree(json),
                    rs.getObject(2, OffsetDateTime.class).toInstant(), rs.getString(3)));
        }, uid);
    }

    /**
     * Merges {@code patch} into the stored profile (top-level keys replace, unknown keys from newer app versions are
     * kept), creating the row if needed.
     */
    public Stored merge(String uid, JsonNode patch, String source) {
        jdbc.update("""
                insert into user_profile (uid, profile, source, created_at, updated_at) values (?, ?::jsonb, ?, now(), now())
                on conflict (uid) do update set
                    profile = coalesce(user_profile.profile, '{}'::jsonb) || excluded.profile,
                    updated_at = now()""", uid, mapper.writeValueAsString(patch), source);
        return find(uid).orElseThrow();
    }

    /** Stores an imported legacy profile unless one appeared meanwhile; returns whatever is stored afterwards. */
    public Stored importIfAbsent(String uid, JsonNode profile, String fcmToken) {
        jdbc.update("""
                insert into user_profile (uid, profile, fcm_token, source) values (?, ?::jsonb, ?, 'firestore_import')
                on conflict (uid) do update set
                    profile = excluded.profile,
                    fcm_token = coalesce(user_profile.fcm_token, excluded.fcm_token),
                    source = 'firestore_import', updated_at = now()
                where user_profile.profile is null""", uid, mapper.writeValueAsString(profile), fcmToken);
        return find(uid).orElseThrow();
    }

    public void setFcmToken(String uid, String token) {
        jdbc.update("""
                insert into user_profile (uid, fcm_token, source) values (?, ?, 'app')
                on conflict (uid) do update set fcm_token = excluded.fcm_token, updated_at = now()""", uid, token);
    }

    /** @return true if a row was deleted */
    public boolean delete(String uid) {
        return jdbc.update("delete from user_profile where uid = ?", uid) == 1;
    }
}
