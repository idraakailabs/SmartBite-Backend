package com.idraak.smartbite.v2.entitlement;

import com.idraak.smartbite.testsupport.MutableClock;
import com.idraak.smartbite.v2.config.V2Properties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementServiceTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    final FakeBilling billing = new FakeBilling();
    final V2Properties.Entitlement cfg = new V2Properties.Entitlement("fake", null, null, null, "smartbite_pro",
            Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofSeconds(30),
            Duration.ofSeconds(2), Duration.ofSeconds(3));
    final EntitlementService service = new EntitlementService(billing, cfg, clock);

    @Test
    void cachesPremiumForFiveMinutes() {
        billing.setPremium("u", true);
        assertThat(service.isPremium("u", false)).isTrue();
        billing.setPremium("u", false);
        clock.advance(Duration.ofMinutes(4));
        assertThat(service.isPremium("u", false)).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.isPremium("u", false)).isFalse();
    }

    @Test
    void cachesFreeForOneMinute() {
        assertThat(service.isPremium("u", false)).isFalse();
        billing.setPremium("u", true);
        clock.advance(Duration.ofSeconds(30));
        assertThat(service.isPremium("u", false)).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(service.isPremium("u", false)).isTrue();
    }

    @Test
    void forcedRefreshBypassesCacheButIsThrottled() {
        assertThat(service.isPremium("u", false)).isFalse();
        billing.setPremium("u", true);
        assertThat(service.isPremium("u", true)).isTrue();
        int reads = billing.reads();
        service.isPremium("u", true);
        assertThat(billing.reads()).isEqualTo(reads); // second forced refresh within 30 s uses the cache
    }

    @Test
    void providerOutageUsesLastKnownValueThenFallsBackToFree() {
        billing.setPremium("u", true);
        service.isPremium("u", false);
        billing.setUnavailable(true);
        clock.advance(Duration.ofHours(2));
        assertThat(service.isPremium("u", false)).isTrue();
        assertThat(service.isPremium("never-seen", false)).isFalse();
    }
}
