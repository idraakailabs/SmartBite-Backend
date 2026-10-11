package com.idraak.smartbite.v2.area;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idraak.smartbite.testsupport.AiStubs;
import com.idraak.smartbite.testsupport.AiStubs.Dish;
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

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End to end with the fake Places client: verified hero promoted, honest labels for the rest. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EatOutEndpointTest {

    static final WireMockServer groq = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    static {
        groq.start();
    }

    @AfterAll
    static void stop() {
        groq.stop();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("groq.base-url", groq::baseUrl);
        registry.add("gemini.base-url", groq::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PlacesClient places;

    @BeforeEach
    void reset() {
        groq.resetAll();
        ((FakePlacesClient) places).reset();
    }

    @Test
    void verifiedAlternativeBecomesTheHero() throws Exception {
        FakePlacesClient fake = (FakePlacesClient) places;
        fake.setNearby(List.of(new PlacesClient.Place("ChIJ-near", 31.4700, 74.2730)));
        fake.setTextResults("Seekh Kebab", List.of("ChIJ-near"));
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(AiStubs.completion(
                AiStubs.suggestionsContent(Dish.meat("Mutton Karahi", "Mutton", "Mutton"), Dish.meat("Seekh Kebab", "Beef", "Beef"),
                        Dish.meat("Chicken Tikka", "Chicken", "Chicken"))))));

        mvc.perform(post("/api/v2/meal-suggestions").header("Authorization", "Bearer test:eatout-1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"intent":"eat_out","timezone":"Asia/Karachi",
                                 "area":{"lat":31.4697,"lng":74.2728,"radius_km":3,"label":"Johar Town, Lahore","city":"Lahore"},
                                 "profile":{"diet":"non_vegetarian"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.area_context.mode").value("coordinates"))
                .andExpect(jsonPath("$.area_context.places_grounding").value("used"))
                .andExpect(jsonPath("$.suggestions[0].name").value("Seekh Kebab"))
                .andExpect(jsonPath("$.suggestions[0].id").value("sug_1"))
                .andExpect(jsonPath("$.suggestions[0].verification.status").value("verified"))
                .andExpect(jsonPath("$.suggestions[0].verification.place_count").value(1))
                .andExpect(jsonPath("$.suggestions[0].verification.place_count_is_lower_bound").value(true))
                .andExpect(jsonPath("$.suggestions[0].verification.attribution.must_display").value(true))
                .andExpect(jsonPath("$.suggestions[0].verification.attribution.text").value("Google Maps"))
                .andExpect(jsonPath("$.suggestions[0].reasons[*].code", hasItem("FOUND_NEARBY")))
                .andExpect(jsonPath("$.suggestions[1].name").value("Mutton Karahi"))
                .andExpect(jsonPath("$.suggestions[1].verification.status").value("popular_around"))
                .andExpect(jsonPath("$.suggestions[1].verification.unverified_reason").value("no_match_found"))
                .andExpect(jsonPath("$.suggestions[2].verification.unverified_reason").value("not_checked"));
    }
}
