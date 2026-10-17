package com.idraak.smartbite.v1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Golden contract for {@code POST /api/v1/meal-suggestions}. Written on Spring Boot 3.4.5 before any upgrade or
 * hardening, and must keep passing unchanged afterwards: request field names, response shape (snake_case,
 * nulls omitted), provider chain order, 429 fallback, and the v1 error bodies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V1GoldenContractTest {

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

    @BeforeEach
    void reset() {
        groq.resetAll();
        gemini.resetAll();
    }

    @Test
    void successResponseMatchesGoldenFileExactly() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(chatCompletion(fenced(read("provider-content.json"))))));

        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("request.json")))
                .andExpect(status().isOk())
                .andReturn();

        JSONAssert.assertEquals(read("response.json"), body(result), JSONCompareMode.STRICT);
        groq.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-groq-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-oss-120b")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user"))));
        gemini.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void falls_back_to_gemini_on_429_then_returns_same_shape() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(aResponse().withStatus(429)));
        gemini.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(chatCompletion(read("provider-content.json")))));

        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("request.json")))
                .andExpect(status().isOk())
                .andReturn();

        JSONAssert.assertEquals(read("response.json"), body(result), JSONCompareMode.STRICT);
        gemini.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-gemini-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gemini-2.5-flash-lite"))));
    }

    @Test
    void falls_back_on_an_invalid_key_and_on_an_unusable_answer() throws Exception {
        // BE-BUG-007: a 401 from Groq used to fail v1 even though Gemini worked.
        // Groq's large model: invalid key. Gemini: an answer that isn't JSON. Groq's small model: a good answer.
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions")
                .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-oss-120b")))
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"Invalid API Key\"}}")));
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions")
                .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-oss-20b")))
                .willReturn(okJson(chatCompletion(read("provider-content.json")))));
        gemini.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(okJson(chatCompletion("not json at all"))));

        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("request.json")))
                .andExpect(status().isOk())
                .andReturn();
        JSONAssert.assertEquals(read("response.json"), body(result), JSONCompareMode.STRICT);
    }

    @Test
    void errorBodiesNeverIncludeProviderText() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions")
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":{\"message\":\"Invalid API Key\"}}")));
        gemini.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(aResponse().withStatus(500)));

        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("request.json")))
                .andExpect(status().isBadGateway())
                .andReturn();
        assertThat(body(result)).doesNotContainIgnoringCase("api key").doesNotContain("groq").doesNotContain("401");
        groq.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));    // gpt-oss-120b and gpt-oss-20b
        gemini.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void aMalformedBodyIs400WithoutParserText() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("42"))
                .andExpect(status().isBadRequest())
                .andReturn();
        JSONObject json = new JSONObject(body(result));
        assertThat(keys(json)).containsExactly("error", "message");
        assertThat(json.getString("error")).isEqualTo("Invalid request body");
        assertThat(body(result)).doesNotContain("JSON parse error");
    }

    @Test
    void aWrongMethodOrContentTypeIsAClientError() throws Exception {
        // Staging, 2026-10-17: a browser GET on the v1 endpoint returned 500.
        mvc.perform(get("/api/v1/meal-suggestions")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/meal-suggestions").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void validationErrorBodyShape() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"country\":\"\",\"age\":0}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        JSONObject json = new JSONObject(body(result));
        assertThat(keys(json)).containsExactly("details", "error");
        assertThat(json.getString("error")).isEqualTo("Validation failed");
        assertThat(keys(json.getJSONObject("details")))
                .contains("country", "city", "cuisine", "gender", "age", "currentTime", "timezone");
    }

    @Test
    void providerErrorBodyShape() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions").willReturn(aResponse().withStatus(500)));

        MvcResult result = mvc.perform(post("/api/v1/meal-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(read("request.json")))
                .andExpect(status().isBadGateway())
                .andReturn();

        JSONObject json = new JSONObject(body(result));
        assertThat(keys(json)).containsExactly("error", "message");
        assertThat(json.getString("error")).isEqualTo("AI service error");
    }

    private static String chatCompletion(String content) throws Exception {
        return new JSONObject()
                .put("choices", new org.json.JSONArray()
                        .put(new JSONObject().put("message", new JSONObject().put("role", "assistant").put("content", content))))
                .toString();
    }

    private static String fenced(String json) {
        return "```json\n" + json + "\n```";
    }

    private static String read(String name) throws IOException {
        return new ClassPathResource("golden/v1/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static String body(MvcResult result) throws IOException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Set<String> keys(JSONObject json) {
        Set<String> keys = new TreeSet<>();
        Iterator<String> it = json.keys();
        it.forEachRemaining(keys::add);
        return keys;
    }
}
