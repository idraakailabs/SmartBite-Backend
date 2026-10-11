package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.entitlement.FakeBilling;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @BeforeEach
    void reset() {
        billing.reset();
    }

    @Test
    void freeUserSeesAllowance() throws Exception {
        mvc.perform(get("/api/v2/me/quota").param("timezone", "Asia/Karachi").header("Authorization", "Bearer test:quota-free"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quota.is_premium").value(false))
                .andExpect(jsonPath("$.quota.unlimited").value(false))
                .andExpect(jsonPath("$.quota.limit").value(3))
                .andExpect(jsonPath("$.quota.used").value(0))
                .andExpect(jsonPath("$.quota.remaining").value(3))
                .andExpect(jsonPath("$.quota.timezone").value("Asia/Karachi"))
                .andExpect(jsonPath("$.quota.day").isString())
                .andExpect(jsonPath("$.quota.reset_at").isString());
    }

    @Test
    void premiumUserIsUnlimited() throws Exception {
        billing.setPremium("quota-pro", true);
        mvc.perform(get("/api/v2/me/quota").param("timezone", "Asia/Karachi").header("Authorization", "Bearer test:quota-pro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quota.is_premium").value(true))
                .andExpect(jsonPath("$.quota.unlimited").value(true))
                .andExpect(jsonPath("$.quota.limit").doesNotExist());
    }

    @Test
    void invalidOrMissingTimezoneIs422() throws Exception {
        mvc.perform(get("/api/v2/me/quota").param("timezone", "+05:00").header("Authorization", "Bearer test:q"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("timezone"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid_timezone"));
        mvc.perform(get("/api/v2/me/quota").header("Authorization", "Bearer test:q"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("required"));
    }
}
