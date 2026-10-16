package com.idraak.smartbite.v2.store;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole service with {@code stores.mode=postgres}: the server's documents live in our database, not Firestore. */
@SpringBootTest(properties = {"smartbite.v2.stores.mode=postgres", "smartbite.v2.stores.collection-prefix=pgmode_"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresModeEndpointTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("smartbite.v2.database.url", TestDatabase::url);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theDocumentStoreIsPostgres() {
        assertThat(store).isInstanceOf(JdbcDocumentStore.class);
    }

    @Test
    void quotaAndReportsWorkOnPostgres() throws Exception {
        String uid = "pg-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(get("/api/v2/me/quota").param("timezone", "Asia/Karachi").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quota.limit").value(3))
                .andExpect(jsonPath("$.quota.used").value(0));

        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:" + uid)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestion_name\":\"Chicken Karahi\",\"reason\":\"other\",\"app_version\":\"2.0.0\"}"))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                "select count(*) from kv_document where collection = 'pgmode_suggestion_reports' and data ->> 'uid' = ?",
                Integer.class, uid)).isEqualTo(1);
    }
}
