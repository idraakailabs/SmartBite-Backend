package com.idraak.smartbite.v2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V2FoundationsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void missingTokenIs401ProblemJson() throws Exception {
        mvc.perform(get("/api/v2/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.type").value("urn:smartbite:problem:unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.request_id").isString());
    }

    @Test
    void aWrongMethodIsAClientErrorInProblemJson() throws Exception {
        // v1 returned 500 for a wrong method (staging, 2026-10-17); v2 answers with a problem+json client error.
        mvc.perform(get("/api/v2/meal-suggestions").header("Authorization", "Bearer test:method-1"))
                .andExpect(status().is4xxClientError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidTokenIs401() throws Exception {
        mvc.perform(get("/api/v2/anything").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void anonymousAccountIs403() throws Exception {
        mvc.perform(get("/api/v2/anything").header("Authorization", "Bearer test:anon-1:anonymous"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_REQUIRED"));
    }

    @Test
    void authenticatedUnknownPathIs404ProblemAndEchoesRequestId() throws Exception {
        String id = "0b7c6f0e-6a1d-4a53-9f2f-1c4b2f3f9a10";
        mvc.perform(get("/api/v2/does-not-exist").header("Authorization", "Bearer test:user-1").header("X-Request-Id", id))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", id))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.request_id").value(id));
    }

    @Test
    void generatesRequestIdWhenAbsentOrInvalid() throws Exception {
        mvc.perform(get("/api/v2/x").header("X-Request-Id", "<script>"))
                .andExpect(header().string("X-Request-Id", matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(40_000) + "\"}";
        mvc.perform(post("/api/v2/meal-suggestions").header("Authorization", "Bearer test:user-1")
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void healthIsPublicAndHidesDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    @Test
    void v1StillNeedsNoToken() throws Exception {
        mvc.perform(post("/api/v1/meal-suggestions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }
}
