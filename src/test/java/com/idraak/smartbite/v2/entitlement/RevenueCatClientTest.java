package com.idraak.smartbite.v2.entitlement;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevenueCatClientTest {

    WireMockServer rc;
    RevenueCatClient client;

    @BeforeEach
    void start() {
        rc = new WireMockServer(WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));
        rc.start();
        client = new RevenueCatClient(rc.baseUrl(), "sk_test_dummy", "proj1", "smartbite_pro",
                Duration.ofSeconds(2), Duration.ofSeconds(1));
        rc.stubFor(get(urlPathEqualTo("/projects/proj1/entitlements")).willReturn(okJson("""
                {"object":"list","items":[
                  {"object":"entitlement","id":"entlOther","lookup_key":"other"},
                  {"object":"entitlement","id":"entlPro123","lookup_key":"smartbite_pro"}]}""")));
    }

    @AfterEach
    void stop() {
        rc.stop();
    }

    @Test
    void premiumWhenActiveEntitlementMatchesResolvedId() {
        rc.stubFor(get("/projects/proj1/customers/uid-1").willReturn(okJson("""
                {"object":"customer","id":"uid-1","active_entitlements":{"object":"list","items":[
                  {"object":"customer.active_entitlement","entitlement_id":"entlPro123","expires_at":1893456000000}]}}""")));
        assertThat(client.isPremium("uid-1")).isTrue();
        rc.verify(getRequestedFor(urlEqualTo("/projects/proj1/customers/uid-1"))
                .withHeader("Authorization", equalTo("Bearer sk_test_dummy")));
    }

    @Test
    void notPremiumForOtherEntitlementsOrUnknownCustomer() {
        rc.stubFor(get("/projects/proj1/customers/uid-2").willReturn(okJson("""
                {"object":"customer","id":"uid-2","active_entitlements":{"object":"list","items":[
                  {"object":"customer.active_entitlement","entitlement_id":"entlOther"}]}}""")));
        rc.stubFor(get("/projects/proj1/customers/uid-3").willReturn(aResponse().withStatus(404)));
        assertThat(client.isPremium("uid-2")).isFalse();
        assertThat(client.isPremium("uid-3")).isFalse();
    }

    @Test
    void serverErrorsAndTimeoutsAreUnavailable() {
        rc.stubFor(get("/projects/proj1/customers/uid-4").willReturn(aResponse().withStatus(503)));
        rc.stubFor(get("/projects/proj1/customers/uid-5").willReturn(okJson("{}").withFixedDelay(2_000)));
        assertThatThrownBy(() -> client.isPremium("uid-4")).isInstanceOf(EntitlementUnavailableException.class);
        assertThatThrownBy(() -> client.isPremium("uid-5")).isInstanceOf(EntitlementUnavailableException.class);
    }

    @Test
    void missingLookupKeyIsUnavailable() {
        rc.stubFor(get(urlPathEqualTo("/projects/proj1/entitlements")).willReturn(okJson("{\"items\":[]}")));
        assertThatThrownBy(() -> client.isPremium("uid-1")).isInstanceOf(EntitlementUnavailableException.class);
    }

    @Test
    void deleteCustomerQueuedOrNotFound() {
        rc.stubFor(delete("/projects/proj1/customers/uid-1").willReturn(okJson("{\"object\":\"customer\",\"id\":\"uid-1\",\"deleted_at\":1}")));
        rc.stubFor(delete("/projects/proj1/customers/uid-9").willReturn(aResponse().withStatus(404)));
        rc.stubFor(delete("/projects/proj1/customers/uid-x").willReturn(aResponse().withStatus(500)));
        assertThat(client.deleteCustomer("uid-1")).isEqualTo(BillingCustomerDeleter.Outcome.DELETION_QUEUED);
        assertThat(client.deleteCustomer("uid-9")).isEqualTo(BillingCustomerDeleter.Outcome.NOT_FOUND);
        assertThatThrownBy(() -> client.deleteCustomer("uid-x")).isInstanceOf(EntitlementUnavailableException.class);
    }
}
