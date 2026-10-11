package com.idraak.smartbite.v2.account;

import com.idraak.smartbite.v2.entitlement.FakeBilling;
import com.idraak.smartbite.v2.quota.QuotaService;
import com.idraak.smartbite.v2.report.ReportController;
import com.idraak.smartbite.v2.store.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "smartbite.v2.limits.delete-per-hour=100")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FakeBilling billing;

    @Autowired
    QuotaService quota;

    @Autowired
    DocumentStore store;

    @Autowired
    AccountDeletionService deletion;

    @BeforeEach
    void reset() {
        billing.reset();
    }

    void report(String uid, String dish) throws Exception {
        mvc.perform(post("/api/v2/reports").header("Authorization", "Bearer test:" + uid).contentType(MediaType.APPLICATION_JSON)
                .content("{\"suggestion_name\":\"" + dish + "\",\"reason\":\"other\",\"app_version\":\"2.1.0\"}")).andExpect(status().isCreated());
    }

    @Test
    void deletesServerDataRevenueCatAndAnonymisesReports() throws Exception {
        String uid = "del-1";
        quota.commit(uid, quota.reserve(uid, ZoneId.of("Asia/Karachi"), null));
        report(uid, "Dish A");
        report(uid, "Dish B");
        report("someone-else", "Dish C");
        billing.setPremium(uid, true);

        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.steps.revenuecat_customer").value("deletion_queued"))
                .andExpect(jsonPath("$.steps.quota_counter").value("deleted"))
                .andExpect(jsonPath("$.steps.reports").value("anonymized"))
                .andExpect(jsonPath("$.reports_anonymized").value(2));

        assertThat(billing.deleted()).containsExactly(uid);
        assertThat(store.get("quota", uid)).isEmpty();
        assertThat(store.findIds(ReportController.COLLECTION, "uid", uid, 10)).isEmpty();
        assertThat(store.findIds(ReportController.COLLECTION, "uid", "someone-else", 10)).hasSize(1);
        assertThat(store.findIds(ReportController.COLLECTION, "suggestion_name", "Dish A", 10)).hasSize(1); // row kept
        assertThat(store.get("deletion_requests", uid)).isEmpty();

        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.revenuecat_customer").value("not_found"))
                .andExpect(jsonPath("$.steps.quota_counter").value("not_found"))
                .andExpect(jsonPath("$.steps.reports").value("none"));
    }

    @Test
    void oldOrRevokedTokensNeedReauthentication() throws Exception {
        long old = Instant.now().minusSeconds(600).getEpochSecond();
        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:del-2:google.com:" + old))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REAUTHENTICATION_REQUIRED"));
        assertThat(billing.deleted()).isEmpty();
    }

    @Test
    void revenueCatOutageIs202AndTheServerRetries() throws Exception {
        String uid = "del-3";
        billing.setPremium(uid, true);
        billing.setUnavailable(true);
        mvc.perform(delete("/api/v2/me").header("Authorization", "Bearer test:" + uid))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending_retry"))
                .andExpect(jsonPath("$.steps.revenuecat_customer").value("retry_scheduled"));
        Map<String, Object> pending = store.get("deletion_requests", uid).orElseThrow();
        assertThat(pending).containsEntry("status", "pending").containsEntry("attempts", 1L);
        assertThat(pending.keySet()).doesNotContain("email", "name");

        billing.setUnavailable(false);
        assertThat(deletion.retryPending()).isGreaterThanOrEqualTo(1);
        assertThat(store.get("deletion_requests", uid)).isEmpty();
        assertThat(billing.deleted()).contains(uid);
    }

    @Test
    void requestsOlderThanTheRetryWindowAreAbandonedForManualAction() {
        store.set("deletion_requests", "del-4", Map.of("status", "pending", "created_at", Instant.now().minusSeconds(3 * 86_400), "attempts", 5L));
        deletion.retryPending();
        assertThat(store.get("deletion_requests", "del-4").orElseThrow()).containsEntry("status", "abandoned");
    }
}
