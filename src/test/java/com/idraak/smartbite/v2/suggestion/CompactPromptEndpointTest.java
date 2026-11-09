package com.idraak.smartbite.v2.suggestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.idraak.smartbite.testsupport.AiStubs;
import com.idraak.smartbite.testsupport.AiStubs.Dish;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** BE-030 with SUGGEST_PROMPT_V3_ENABLED on: the compact prompt, lean data and low reasoning reach the providers. */
@SpringBootTest(properties = {"smartbite.v2.limits.suggestions-per-minute=100", "smartbite.v2.ai.prompt-v3-enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompactPromptEndpointTest {

    static final WireMockServer groq = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
    static final WireMockServer gemini = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    static {
        groq.start();
        gemini.start();
    }

    @AfterAll
    static void stop() {
        groq.stop();
        gemini.stop();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("groq.base-url", groq::baseUrl);
        registry.add("gemini.base-url", gemini::baseUrl);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @BeforeEach
    void reset() {
        groq.resetAll();
        gemini.resetAll();
        billing.reset();
    }

    static final String NEW_USER_EAT_OUT = """
            {"intent":"eat_out","timezone":"Asia/Karachi","area":{"city":"Lahore","country":"Pakistan"},
             "profile_status":"empty","profile":{"diet":"non_vegetarian","diet_is_explicit":false}}""";

    @Test
    void theCompactPromptIsUsedAndReported() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(AiStubs.completion(
                AiStubs.suggestionsContent(Dish.meat("Chicken Karahi", "Chicken", "Chicken"),
                        Dish.meat("Seekh Kebab", "Beef", "Beef"), Dish.veg("Daal Mash", "Urad daal"))))));
        mvc.perform(post("/api/v2/meal-suggestions").header("Authorization", "Bearer test:v3-u1")
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_USER_EAT_OUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", hasSize(3)))
                .andExpect(jsonPath("$.meta.prompt_version").value("suggest-v3.0"));
        groq.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("New user or few preferences"))
                .withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("low")))
                .withRequestBody(containing("profile_status\\\":\\\"empty"))
                .withRequestBody(notContaining("favourite_cuisines\\\":[]")));
    }

    @Test
    void geminiNeverGetsReasoningEffort() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(aResponse().withStatus(503)));
        gemini.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(AiStubs.completion(
                AiStubs.suggestionsContent(Dish.meat("Chicken Karahi", "Chicken", "Chicken"),
                        Dish.meat("Seekh Kebab", "Beef", "Beef"), Dish.veg("Daal Mash", "Urad daal"))))));
        mvc.perform(post("/api/v2/meal-suggestions").header("Authorization", "Bearer test:v3-u2")
                        .contentType(MediaType.APPLICATION_JSON).content(NEW_USER_EAT_OUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.degraded").value(true))
                .andExpect(jsonPath("$.meta.prompt_version").value("suggest-v3.0"));
        gemini.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("New user or few preferences"))
                .withRequestBody(notContaining("reasoning_effort")));
    }
}
