package com.idraak.smartbite.v2.report;

import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.InMemoryDocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentStore store;

    static final String BODY = """
            {"suggestion_name":"Chicken Karahi","reason":"violates_my_diet","app_version":"2.1.0","extra_field":"ignored"}""";

    @Test
    void storesExactlyTheAllowedFields() throws Exception {
        MvcResult r = mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:rep-1")
                        .header("X-Forwarded-For", "203.0.113.5")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.report_id").isString())
                .andExpect(jsonPath("$.created_at").isString())
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.report_id");
        Map<String, Object> doc = store.get(ReportController.COLLECTION, id).orElseThrow();
        assertThat(doc.keySet()).containsExactlyInAnyOrder("uid", "suggestion_name", "reason", "created_at", "app_version");
        assertThat(doc).containsEntry("uid", "rep-1").containsEntry("reason", "violates_my_diet")
                .containsEntry("suggestion_name", "Chicken Karahi").containsEntry("app_version", "2.1.0");
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginal() throws Exception {
        String key = UUID.randomUUID().toString();
        int before = ((InMemoryDocumentStore) store).count(ReportController.COLLECTION);
        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:rep-2").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isCreated());
        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:rep-2").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        assertThat(((InMemoryDocumentStore) store).count(ReportController.COLLECTION)).isEqualTo(before + 1);
    }

    @Test
    void validatesReasonAndVersion() throws Exception {
        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:rep-3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestion_name\":\"X\",\"reason\":\"spam\",\"app_version\":\"2.1.0\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:rep-3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestion_name\":\"X\",\"reason\":\"other\",\"app_version\":\"latest\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("app_version"));
    }

    @Test
    void needsAuth() throws Exception {
        mvc.perform(post("/api/v2/reports").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
