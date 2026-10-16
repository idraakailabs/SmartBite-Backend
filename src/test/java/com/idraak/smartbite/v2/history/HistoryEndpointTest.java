package com.idraak.smartbite.v2.history;

import com.idraak.smartbite.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /api/v2/me/history} on a real PostgreSQL (D-024): sync, isolation, latest-change-wins, retention. */
@SpringBootTest(properties = {"smartbite.v2.limits.history-per-minute=1000", "smartbite.v2.limits.delete-per-hour=100"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryEndpointTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("smartbite.v2.database.url", TestDatabase::url);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    HistoryService history;

    final JsonMapper json = JsonMapper.builder().build();

    static String user() {
        return "hist-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String entry(String id, String name, Instant loggedAt, Instant updatedAt, boolean deleted) {
        return """
                {"id":"%s","name":"%s","entry_type":"cooked","meal_type":"Dinner","reaction":"loved","servings":4,
                 "area":null,"note":"less salt","tags":["home"],"logged_at":"%s","updated_at":"%s","deleted":%s}"""
                .formatted(id, name, loggedAt, updatedAt, deleted);
    }

    ResultActions push(String uid, String entries, String favorites) throws Exception {
        return mvc.perform(put("/api/v2/me/history").header("Authorization", "Bearer test:" + uid)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[" + entries + "],\"favorites\":[" + favorites + "]}"));
    }

    JsonNode pull(String uid, String cursor) throws Exception {
        var req = get("/api/v2/me/history").header("Authorization", "Bearer test:" + uid);
        if (cursor != null) req.param("cursor", cursor);
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /** Waits out the 2-second settle window, so just-written rows are readable. */
    static void settle() throws InterruptedException {
        Thread.sleep(2_100);
    }

    @Test
    void signInIsRequired() throws Exception {
        mvc.perform(get("/api/v2/me/history")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void entriesAndFavouritesRoundTripToAnotherDevice() throws Exception {
        String uid = user();
        Instant logged = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        push(uid, entry("e1", "Chicken Karahi", logged, logged, false),
                "{\"name\":\"Chicken Karahi\",\"favorite\":true,\"updated_at\":\"" + logged + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries_applied").value(1))
                .andExpect(jsonPath("$.favorites_applied").value(1));
        settle();

        JsonNode page = pull(uid, null);
        assertThat(page.at("/entries/0/id").asString()).isEqualTo("e1");
        assertThat(page.at("/entries/0/name").asString()).isEqualTo("Chicken Karahi");
        assertThat(page.at("/entries/0/entry_type").asString()).isEqualTo("cooked");
        assertThat(page.at("/entries/0/reaction").asString()).isEqualTo("loved");
        assertThat(page.at("/entries/0/servings").asInt()).isEqualTo(4);
        assertThat(page.at("/entries/0/note").asString()).isEqualTo("less salt");
        assertThat(Instant.parse(page.at("/entries/0/logged_at").asString())).isEqualTo(logged);
        assertThat(page.at("/favorites/0").asString()).isEqualTo("Chicken Karahi");
        assertThat(page.at("/has_more").asBoolean()).isFalse();
        assertThat(page.at("/retention_months").asInt()).isEqualTo(24);

        // Nothing new since the cursor.
        JsonNode again = pull(uid, page.at("/next_cursor").asString());
        assertThat(again.at("/entries").size()).isZero();
        assertThat(again.at("/next_cursor").asString()).isEqualTo(page.at("/next_cursor").asString());
    }

    @Test
    void theLatestClientChangeWinsAndDeletionsReachOtherDevices() throws Exception {
        String uid = user();
        Instant logged = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Instant t1 = logged.plusSeconds(10);
        Instant t2 = logged.plusSeconds(20);
        push(uid, entry("e1", "Daal", logged, t2, false), "").andExpect(status().isOk());
        // An older edit from a slower device is skipped.
        push(uid, entry("e1", "Old name", logged, t1, false), "")
                .andExpect(jsonPath("$.entries_applied").value(0))
                .andExpect(jsonPath("$.entries_skipped").value(1));
        settle();
        JsonNode first = pull(uid, null);
        assertThat(first.at("/entries/0/name").asString()).isEqualTo("Daal");

        // A deletion is a tombstone that the other device receives after its cursor.
        push(uid, entry("e1", "Daal", logged, t2.plusSeconds(5), true), "").andExpect(jsonPath("$.entries_applied").value(1));
        settle();
        JsonNode next = pull(uid, first.at("/next_cursor").asString());
        assertThat(next.at("/entries").size()).isEqualTo(1);
        assertThat(next.at("/entries/0/deleted").asBoolean()).isTrue();
    }

    @Test
    void usersNeverSeeOrChangeEachOthersHistory() throws Exception {
        String a = user();
        String b = user();
        Instant logged = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        push(a, entry("same-id", "A's meal", logged, logged, false), "").andExpect(status().isOk());
        push(b, entry("same-id", "B's meal", logged, logged.plusSeconds(60), false), "").andExpect(status().isOk());
        settle();
        assertThat(pull(a, null).at("/entries/0/name").asString()).isEqualTo("A's meal");
        assertThat(pull(b, null).at("/entries/0/name").asString()).isEqualTo("B's meal");
        assertThat(pull(a, null).at("/entries").size()).isEqualTo(1);
    }

    @Test
    void pagesFollowTheCursor() throws Exception {
        String uid = user();
        Instant logged = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) entries.add(entry("p" + i, "Dish " + i, logged.plusSeconds(i), logged, false));
        push(uid, String.join(",", entries), "").andExpect(jsonPath("$.entries_applied").value(5));
        settle();

        List<String> seen = new ArrayList<>();
        String cursor = null;
        boolean more = true;
        while (more) {
            var req = get("/api/v2/me/history").header("Authorization", "Bearer test:" + uid).param("limit", "2");
            if (cursor != null) req.param("cursor", cursor);
            JsonNode page = json.readTree(mvc.perform(req).andReturn().getResponse().getContentAsString());
            page.at("/entries").forEach(e -> seen.add(e.at("/id").asString()));
            cursor = page.at("/next_cursor").asString();
            more = page.at("/has_more").asBoolean();
        }
        assertThat(seen).containsExactlyInAnyOrder("p0", "p1", "p2", "p3", "p4");
    }

    @Test
    void aMalformedCursorIsRejected() throws Exception {
        mvc.perform(get("/api/v2/me/history").header("Authorization", "Bearer test:" + user()).param("cursor", "not-a-cursor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void oddEntriesAreTidiedAndBrokenOnesRejectedWithoutBlockingTheRest() throws Exception {
        String uid = user();
        Instant logged = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        String odd = """
                {"id":"ok-1","name":"  Biryani\\u0007  ","entry_type":"something_new","reaction":"amazing","calories":-5,
                 "logged_at":"%s","updated_at":"%s","deleted":false}""".formatted(logged, logged);
        String noName = """
                {"id":"bad-1","name":"   ","logged_at":"%s","updated_at":"%s","deleted":false}""".formatted(logged, logged);
        String badId = """
                {"id":"has spaces!","name":"Pulao","logged_at":"%s","updated_at":"%s","deleted":false}""".formatted(logged, logged);
        push(uid, odd + "," + noName + "," + badId, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries_applied").value(1))
                .andExpect(jsonPath("$.rejected_ids.length()").value(2));
        settle();
        JsonNode e = pull(uid, null).at("/entries/0");
        assertThat(e.at("/name").asString()).isEqualTo("Biryani");
        assertThat(e.at("/entry_type").asString()).isEqualTo("logged");
        assertThat(e.at("/reaction").isMissingNode() || e.at("/reaction").isNull()).isTrue();
        assertThat(e.at("/calories").isMissingNode() || e.at("/calories").isNull()).isTrue();
    }

    @Test
    void aFullBatchWithLongNotesFitsInOneRequest() throws Exception {
        String uid = user();
        Instant t = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        String note = "n".repeat(500);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add("""
                    {"id":"f%d","name":"%s","entry_type":"ate_out","area":"%s","note":"%s","tags":["a","b"],
                     "logged_at":"%s","updated_at":"%s","deleted":false}""".formatted(i, "D".repeat(120), "A".repeat(120), note, t, t));
        }
        push(uid, String.join(",", entries), "").andExpect(status().isOk()).andExpect(jsonPath("$.entries_applied").value(100));
    }

    @Test
    void tooManyEntriesInOneRequestAreRejected() throws Exception {
        String uid = user();
        Instant t = Instant.now().minus(1, ChronoUnit.DAYS);
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < 101; i++) entries.add(entry("n" + i, "Dish", t, t, false));
        push(uid, String.join(",", entries), "").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void entriesOlderThan24MonthsAreNotStoredAndCleanupRemovesThem() throws Exception {
        String uid = user();
        Instant old = Instant.now().atZone(ZoneOffset.UTC).minusMonths(25).toInstant().truncatedTo(ChronoUnit.MILLIS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        push(uid, entry("old-1", "Old dish", old, recent, false) + "," + entry("new-1", "New dish", recent, recent, false), "")
                .andExpect(jsonPath("$.entries_applied").value(1))
                .andExpect(jsonPath("$.entries_skipped").value(1));

        // A row that aged past the window after it was stored is removed by the daily job.
        jdbc.update("update meal_history set logged_at = now() - interval '25 months' where uid = ? and id = 'new-1'", uid);
        history.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from meal_history where uid = ?", Integer.class, uid)).isZero();
    }

    @Test
    void accountsInactiveFor24MonthsLoseTheirCloudHistory() throws Exception {
        String uid = user();
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        push(uid, entry("i-1", "Dish", recent, recent, false),
                "{\"name\":\"Dish\",\"favorite\":true,\"updated_at\":\"" + recent + "\"}").andExpect(status().isOk());
        jdbc.update("update user_activity set last_seen_at = now() - interval '25 months' where uid = ?", uid);

        history.cleanup();

        assertThat(jdbc.queryForObject("select count(*) from meal_history where uid = ?", Integer.class, uid)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from favorite_dish where uid = ?", Integer.class, uid)).isZero();
    }

    @Test
    void accountDeletionRemovesHistoryAndFavourites() throws Exception {
        String uid = user();
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        push(uid, entry("d-1", "Dish", recent, recent, false),
                "{\"name\":\"Dish\",\"favorite\":true,\"updated_at\":\"" + recent + "\"}").andExpect(status().isOk());

        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.user_data").value("deleted"));

        assertThat(jdbc.queryForObject("select count(*) from meal_history where uid = ?", Integer.class, uid)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from favorite_dish where uid = ?", Integer.class, uid)).isZero();
    }
}
