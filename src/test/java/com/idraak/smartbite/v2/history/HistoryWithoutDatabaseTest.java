package com.idraak.smartbite.v2.history;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With no DATABASE_URL the service still starts; the endpoints say the feature isn't available yet. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryWithoutDatabaseTest {

    @Autowired
    MockMvc mvc;

    @Test
    void historyAndProfileAnswer503FeatureUnavailable() throws Exception {
        mvc.perform(get("/api/v2/me/history").header("Authorization", "Bearer test:nodb"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"));
        mvc.perform(get("/api/v2/me/profile").header("Authorization", "Bearer test:nodb"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"));
    }
}
