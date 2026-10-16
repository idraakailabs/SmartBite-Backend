package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.entitlement.FakeBilling;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staging shares the RevenueCat project with production, so deletion there must leave the customer alone. */
@SpringBootTest(properties = {"smartbite.v2.limits.delete-per-hour=100", "smartbite.v2.deletion.billing-deletion-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionWithoutBillingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @BeforeEach
    void reset() {
        billing.reset();
    }

    @Test
    void serverDataIsDeletedButTheRevenueCatCustomerIsNot() throws Exception {
        billing.setPremium("stg-1", true);

        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:stg-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.steps.revenuecat_customer").value("skipped"));

        assertThat(billing.deleted()).isEmpty();
    }
}
