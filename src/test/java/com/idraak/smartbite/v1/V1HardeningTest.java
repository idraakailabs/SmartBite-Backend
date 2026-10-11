package com.idraak.smartbite.v1;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** D-021 item 4: AI timeouts, per-IP limit and CORS narrowed to /api/v1/**. */
@SpringBootTest(properties = {
        "smartbite.v1.rate-limit-per-minute=2",
        "smartbite.v1.ai-read-timeout=1s"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V1HardeningTest {

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

    @BeforeEach
    void reset() {
        groq.resetAll();
    }

    @Test
    void corsPreflightAllowedOnV1() throws Exception {
        mvc.perform(options("/api/v1/meal-suggestions")
                        .header("Origin", "https://example.org")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void noCorsOutsideV1() throws Exception {
        mvc.perform(options("/api/v2/meal-suggestions")
                        .header("Origin", "https://example.org")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void perIpLimitReturns429InV1ErrorStyle() throws Exception {
        String ip = "198.51.100.9";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/meal-suggestions").with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/meal-suggestions").with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("Too many requests"));
    }

    @Test
    void hungProviderTimesOutInsteadOfHanging() throws Exception {
        groq.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post("/chat/completions")
                .willReturn(aResponse().withFixedDelay(3_000).withStatus(200).withBody("{}")));
        String request = new ClassPathResource("golden/v1/request.json").getContentAsString(StandardCharsets.UTF_8);

        long start = System.nanoTime();
        mvc.perform(post("/api/v1/meal-suggestions").with(r -> { r.setRemoteAddr("198.51.100.77"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Internal server error"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(2_900);
    }
}
