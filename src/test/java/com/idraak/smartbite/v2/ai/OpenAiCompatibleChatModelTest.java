package com.idraak.smartbite.v2.ai;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleChatModelTest {

    final ObjectMapper mapper = JsonMapper.builder().build();
    WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    static String completion(String content, String finishReason) {
        return "{\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"" + content.replace("\"", "\\\"") + "\"}}]}";
    }

    @Test
    void reasoningHeadRoomIsAddedToMaxTokens() {
        // BE-BUG-014: Gemini 3's reasoning counts against max_tokens and cut a recipe's JSON off (staging, 2026-10-17).
        server.stubFor(post("/chat/completions").willReturn(okJson(completion("{}", "stop"))));
        var gemini = new OpenAiCompatibleChatModel("gemini", "gemini-3.5-flash-lite", server.baseUrl(), "k", false,
                6000, Duration.ofSeconds(2), mapper);
        var groq = new OpenAiCompatibleChatModel("groq", "openai/gpt-oss-120b", server.baseUrl(), "k", true,
                Duration.ofSeconds(2), mapper);

        gemini.complete("system", "user", Duration.ofSeconds(5), 4000);
        groq.complete("system", "user", Duration.ofSeconds(5), 4000);

        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gemini-3.5-flash-lite")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("10000"))));
        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-oss-120b")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4000"))));
    }

    @Test
    void cutOffOutputIsStillReturnedForTheParserToReject() {
        server.stubFor(post("/chat/completions").willReturn(okJson(completion("{\"steps\":[{\"instr", "length"))));
        var model = new OpenAiCompatibleChatModel("gemini", "m", server.baseUrl(), "k", false, Duration.ofSeconds(2), mapper);

        assertThat(model.complete("system", "user", Duration.ofSeconds(5), 100)).startsWith("{\"steps\"");
    }
}
