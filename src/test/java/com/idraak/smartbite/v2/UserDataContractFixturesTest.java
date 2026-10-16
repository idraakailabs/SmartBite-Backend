package com.idraak.smartbite.v2;

import com.idraak.smartbite.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The backend half of the D-024 user-data contract (Android {@code UserDataContractTest}). The app's request bodies
 * in {@code contract/android/} (history_push, profile_put) go to the real endpoints on PostgreSQL; the responses are
 * recorded in {@code contract/server/} (or compared by shape, as in {@link ContractFixturesTest}). The clock is
 * fixed to the fixtures' date, so their entries stay inside the 24-month window whenever this runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserDataContractFixturesTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixtureClock() {
            return Clock.fixed(Instant.parse("2026-10-11T19:00:00Z"), ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("smartbite.v2.database.url", TestDatabase::url);
    }

    @Autowired
    MockMvc mvc;

    final String uid = "contract-" + UUID.randomUUID().toString().substring(0, 8);

    String call(MockHttpServletRequestBuilder request, String fixture) throws Exception {
        if (fixture != null) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(Files.readString(ContractFixturesTest.ANDROID.resolve(fixture + ".json")));
        }
        var result = mvc.perform(request.header("Authorization", "Bearer test:" + uid)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(body).isBetween(200, 299);
        return body;
    }

    @Test
    void historyAndProfileRequestsFromTheAppAreAcceptedAndRecorded() throws Exception {
        ContractFixturesTest.checkRecorded("history_push_result", call(put("/api/v2/me/history"), "history_push"));
        Thread.sleep(2_100); // the page leaves out rows changed in the last two seconds
        String page = call(get("/api/v2/me/history"), null);
        assertThat(ContractFixturesTest.JSON.readTree(page).at("/entries").size()).isEqualTo(2);
        ContractFixturesTest.checkRecorded("history_page", page);
        ContractFixturesTest.checkRecorded("profile", call(put("/api/v2/me/profile"), "profile_put"));
    }
}
