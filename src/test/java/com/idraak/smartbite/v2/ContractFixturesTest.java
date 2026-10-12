package com.idraak.smartbite.v2;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idraak.smartbite.testsupport.AiStubs;
import com.idraak.smartbite.testsupport.AiStubs.Dish;
import com.idraak.smartbite.v2.area.FakePlacesClient;
import com.idraak.smartbite.v2.area.PlacesClient;
import com.idraak.smartbite.v2.entitlement.FakeBilling;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The backend half of the v2 contract with the Android app (see docs/TECHNICAL.md, "Contract with the app").
 *
 * <p>The request bodies in {@code src/test/resources/contract/android/} are produced by the app's real
 * repository (Android {@code V2ContractTest}). Each is posted to the real endpoint here and must be accepted.
 * The responses are recorded in {@code contract/server/}; the app parses those same files. When a recorded
 * file exists, the live response must keep the same JSON shape (field paths and types), so a contract change
 * on either side fails a test. To re-record after a deliberate change, delete the server files (or run with
 * {@code -Dcontract.write=true}) and copy them to the Android repo's {@code app/src/test/resources/contract/server/}.
 */
@SpringBootTest(properties = "smartbite.v2.limits.suggestions-per-minute=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContractFixturesTest {

    static final WireMockServer ai = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
    static final Path ANDROID = Path.of("src/test/resources/contract/android");
    static final Path SERVER = Path.of("src/test/resources/contract/server");
    static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        ai.start();
    }

    @AfterAll
    static void stop() {
        ai.stop();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("groq.base-url", ai::baseUrl);
        registry.add("gemini.base-url", ai::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @Autowired
    PlacesClient places;

    @BeforeEach
    void reset() {
        ai.resetAll();
        billing.reset();
        ((FakePlacesClient) places).reset();
    }

    static final String RECIPE = """
            {"diet_conflict":false,"diet":"non_vegetarian","contains_meat_or_fish":true,"contains_egg":false,"contains_dairy":true,
             "difficulty":"medium","times":{"prep_minutes":15,"cook_minutes":35,"rest_minutes":0,"total_minutes":50},
             "ingredient_groups":[{"title":"For the karahi","items":[
               {"item":"Chicken, bone-in","display_quantity":"4 pao","quantity":4,"unit":"pao","metric":{"quantity":1000,"unit":"g"},
                "notes":null,"optional":false,"is_substitution":false,"substitution_for":null,"substitutes":[]},
               {"item":"Salt","display_quantity":"to taste","quantity":null,"unit":null,"metric":{"quantity":null,"unit":"to_taste"},
                "optional":false,"is_substitution":false}]}],
             "steps":[
               {"number":1,"instruction":"Heat the oil.","key_quantities":[],"cook_minutes":2},
               {"number":2,"instruction":"Add the chicken and fry.","key_quantities":["1 kg chicken"],"cook_minutes":8,"timer_seconds":480},
               {"number":3,"instruction":"Add tomatoes.","key_quantities":[]},
               {"number":4,"instruction":"Finish with ginger.","key_quantities":[]}],
             "tips":["Use a heavy karahi."],
             "local_variant":null}""";

    void stubAi(String content) {
        ai.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(AiStubs.completion(content))));
    }

    void stubThreeDishes() {
        stubAi(AiStubs.suggestionsContent(
                Dish.meat("Chicken Karahi", "Chicken", "Chicken", "Tomatoes"),
                Dish.meat("Seekh Kebab", "Beef", "Beef"),
                Dish.veg("Daal Mash", "Urad daal", "Butter")));
    }

    /** Posts an Android fixture, expects 2xx, and records or checks the response shape. */
    void replay(String fixture, String recordAs, MockHttpServletRequestBuilder request) throws Exception {
        if (fixture != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(Files.readString(ANDROID.resolve(fixture + ".json")));
        }
        var result = mvc.perform(request.header("Authorization", "Bearer test:contract-" + recordAs)).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as("%s → %s", recordAs, body).isBetween(200, 299);
        check(recordAs, body);
    }

    void check(String name, String body) throws IOException {
        Path file = SERVER.resolve(name + ".json");
        JsonNode live = JSON.readTree(body);
        if (!Files.exists(file) || Boolean.getBoolean("contract.write")) {
            Files.createDirectories(SERVER);
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(live) + "\n");
            return;
        }
        assertThat(shape(live)).as("JSON shape of %s changed; re-record and update the app", name)
                .isEqualTo(shape(JSON.readTree(Files.readString(file))));
    }

    /** Field paths with their JSON types, array indices collapsed: a stable fingerprint of the contract. */
    static TreeSet<String> shape(JsonNode node) {
        TreeSet<String> out = new TreeSet<>();
        walk("$", node, out);
        return out;
    }

    static void walk(String path, JsonNode node, TreeSet<String> out) {
        if (node.isObject()) {
            out.add(path + ":object");
            for (var entry : node.properties()) walk(path + "." + entry.getKey(), entry.getValue(), out);
        } else if (node.isArray()) {
            out.add(path + ":array");
            for (JsonNode child : node) walk(path + "[]", child, out);
        } else if (!node.isNull()) {
            out.add(path + ":" + node.getNodeType().name().toLowerCase());
        }
    }

    @Test
    void cookRequestFromTheAppIsAcceptedAndRecorded() throws Exception {
        stubThreeDishes();
        replay("suggest_cook", "suggest_cook", post("/api/v2/meal-suggestions").header("Idempotency-Key", "6b1f0d2e-1c2a-4f6e-9a51-0c4b7f3d2e11"));
    }

    @Test
    void eatOutRequestFromTheAppIsAcceptedAndRecordedWithAVerifiedPick() throws Exception {
        FakePlacesClient fake = (FakePlacesClient) places;
        fake.setNearby(List.of(new PlacesClient.Place("ChIJ-near", 31.4700, 74.2730)));
        fake.setTextResults("Seekh Kebab", List.of("ChIJ-near"));
        stubThreeDishes();
        replay("suggest_eat_out", "suggest_eat_out", post("/api/v2/meal-suggestions").header("Idempotency-Key", "6b1f0d2e-1c2a-4f6e-9a51-0c4b7f3d2e12"));
        String recorded = Files.readString(SERVER.resolve("suggest_eat_out.json"));
        assertThat(JSON.readTree(recorded).at("/suggestions/0/verification/status").asString()).isEqualTo("verified");
    }

    @Test
    void anywhereRequestFromTheAppIsAccepted() throws Exception {
        stubThreeDishes();
        replay("suggest_anywhere", "suggest_anywhere", post("/api/v2/meal-suggestions").header("Idempotency-Key", "6b1f0d2e-1c2a-4f6e-9a51-0c4b7f3d2e13"));
    }

    @Test
    void recipeRequestFromTheAppIsAcceptedAndLockedForFreeUsers() throws Exception {
        stubAi(RECIPE);
        replay("recipe", "recipe_free", post("/api/v2/recipes"));
        assertThat(JSON.readTree(Files.readString(SERVER.resolve("recipe_free.json"))).at("/locked").asBoolean()).isTrue();
    }

    @Test
    void reportRequestFromTheAppIsAccepted() throws Exception {
        replay("report", "report", post("/api/v2/reports").header("Idempotency-Key", "6b1f0d2e-1c2a-4f6e-9a51-0c4b7f3d2e14"));
    }

    @Test
    void quotaAndAccountDeletionResponsesAreRecorded() throws Exception {
        replay(null, "quota", get("/api/v2/me/quota").param("timezone", "Asia/Karachi"));
        replay(null, "delete_me", delete("/api/v2/me"));
    }
}
