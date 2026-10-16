package com.idraak.smartbite.v2.profile;

import com.idraak.smartbite.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/v2/me/profile} and {@code /device} on a real PostgreSQL, with a stand-in for legacy Firestore. */
@SpringBootTest(properties = {"smartbite.v2.limits.profile-per-minute=1000", "smartbite.v2.limits.delete-per-hour=100"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileEndpointTest {

    /** The app's legacy {@code users/{uid}} documents, as Firestore returns them. */
    static class LegacyDocs implements LegacyProfileSource {
        final Map<String, Map<String, Object>> docs = new ConcurrentHashMap<>();
        int reads;

        @Override
        public Optional<Map<String, Object>> fetch(String uid) {
            reads++;
            return Optional.ofNullable(docs.get(uid));
        }

        @Override
        public boolean delete(String uid) {
            return docs.remove(uid) != null;
        }
    }

    @TestConfiguration
    static class Legacy {
        @Bean
        @Primary
        LegacyDocs legacyDocs() {
            return new LegacyDocs();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("smartbite.v2.database.url", TestDatabase::url);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LegacyDocs legacy;

    @Autowired
    JdbcTemplate jdbc;

    static String user() {
        return "prof-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static final String PROFILE = """
            {"profile":{"name":"Ali","dietPreference":"non_vegetarian","city":"Lahore","topCuisines":["Pakistani"],
             "numberOfPeople":4,"isOnboardingComplete":true}}""";

    @Test
    void aUserWithNoProfileAnywhereGets404() throws Exception {
        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theProfileIsStoredAsTheAppSendsIt() throws Exception {
        String uid = user();
        mvc.perform(put("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                // The app's own keys come back unchanged (not snake_cased).
                .andExpect(jsonPath("$.profile.dietPreference").value("non_vegetarian"))
                .andExpect(jsonPath("$.profile.isOnboardingComplete").value(true))
                .andExpect(jsonPath("$.profile.topCuisines[0]").value("Pakistani"))
                .andExpect(jsonPath("$.updated_at").exists());
    }

    @Test
    void writesMergeSoKeysFromNewerAppVersionsSurvive() throws Exception {
        String uid = user();
        mvc.perform(put("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)
                .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{\"city\":\"Lahore\",\"futureField\":1}}"));
        mvc.perform(put("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{\"city\":\"Karachi\"}}"))
                .andExpect(jsonPath("$.profile.city").value("Karachi"))
                .andExpect(jsonPath("$.profile.futureField").value(1));
    }

    @Test
    void theLegacyFirestoreProfileIsImportedOnceOnFirstRead() throws Exception {
        String uid = user();
        Map<String, Object> doc = new HashMap<>();
        doc.put("name", "Sara");
        doc.put("dietPreference", "vegetarian");
        doc.put("topCuisines", List.of("Pakistani", "Italian"));
        doc.put("numberOfPeople", 3L);
        doc.put("weightKg", 61.5);
        doc.put("onboardingComplete", true);       // how Firestore names the Kotlin property isOnboardingComplete
        doc.put("fcmToken", "token-abc");
        legacy.docs.put(uid, doc);
        int before = legacy.reads;

        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.name").value("Sara"))
                .andExpect(jsonPath("$.profile.isOnboardingComplete").value(true))
                .andExpect(jsonPath("$.profile.numberOfPeople").value(3))
                .andExpect(jsonPath("$.profile.weightKg").value(61.5))
                .andExpect(jsonPath("$.profile.fcmToken").doesNotExist());
        assertThat(jdbc.queryForObject("select fcm_token from user_profile where uid = ?", String.class, uid)).isEqualTo("token-abc");
        assertThat(jdbc.queryForObject("select source from user_profile where uid = ?", String.class, uid)).isEqualTo("firestore_import");

        // The second read comes from our database; Firestore isn't read again and isn't changed.
        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)).andExpect(status().isOk());
        assertThat(legacy.reads).isEqualTo(before + 1);
        assertThat(legacy.docs).containsKey(uid);
    }

    @Test
    void aDeviceTokenAloneDoesNotCountAsAProfile() throws Exception {
        String uid = user();
        mvc.perform(put("/api/v2/me/device").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcm_token\":\"t-123\"}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select fcm_token from user_profile where uid = ?", String.class, uid)).isEqualTo("t-123");
        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)).andExpect(status().isNotFound());
    }

    @Test
    void invalidBodiesAreRejected() throws Exception {
        String uid = user();
        mvc.perform(put("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":[1,2]}"))
                .andExpect(status().isUnprocessableEntity());
        String big = "x".repeat(17_000);
        mvc.perform(put("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"profile\":{\"note\":\"" + big + "\"}}"))
                .andExpect(status().is4xxClientError());
        mvc.perform(put("/api/v2/me/device").header("Authorization", "Bearer test:" + uid)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fcm_token\":\"has space\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void accountDeletionRemovesTheProfileAndTheLegacyDocument() throws Exception {
        String uid = user();
        legacy.docs.put(uid, new HashMap<>(Map.of("name", "Zed")));
        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:" + uid)).andExpect(status().isOk());

        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.user_data").value("deleted"));

        assertThat(jdbc.queryForObject("select count(*) from user_profile where uid = ?", Integer.class, uid)).isZero();
        assertThat(legacy.docs).doesNotContainKey(uid);
    }
}
