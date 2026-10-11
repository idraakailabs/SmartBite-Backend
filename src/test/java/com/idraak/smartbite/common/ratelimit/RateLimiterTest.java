package com.idraak.smartbite.common.ratelimit;

import com.idraak.smartbite.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));

    @Test
    void allowsUpToPermitsThenRejectsWithRetryAfter() {
        RateLimiter limiter = new RateLimiter(List.of(RateLimitSpec.perMinute(3)), clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        RateLimitDecision rejected = limiter.tryAcquire("a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isBetween(1L, 20L);
    }

    @Test
    void refillsOverTime() {
        RateLimiter limiter = new RateLimiter(List.of(RateLimitSpec.perMinute(2)), clock);
        limiter.tryAcquire("a");
        limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(List.of(RateLimitSpec.perMinute(1)), clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void everySpecMustHaveATokenAndRejectedCallsConsumeNothing() {
        RateLimiter limiter = new RateLimiter(List.of(RateLimitSpec.perMinute(10), RateLimitSpec.perHour(2)), clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        RateLimitDecision d = limiter.tryAcquire("a");
        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isGreaterThan(60);
    }

    @Test
    void evictResetsAKey() {
        RateLimiter limiter = new RateLimiter(List.of(RateLimitSpec.perMinute(1)), clock);
        limiter.tryAcquire("a");
        limiter.evict("a");
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }
}
