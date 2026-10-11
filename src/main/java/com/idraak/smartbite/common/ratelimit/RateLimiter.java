package com.idraak.smartbite.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * In-memory token-bucket limiter. A key passes only if every spec has a token; tokens are taken from all buckets
 * together. State is per instance and is lost on restart (acceptable for burst limits; durable caps live in
 * Firestore). Idle keys are evicted after the longest period.
 */
public class RateLimiter {

    private final List<RateLimitSpec> specs;
    private final Clock clock;
    private final Cache<String, Buckets> buckets;

    public RateLimiter(List<RateLimitSpec> specs, Clock clock) {
        if (specs.isEmpty()) throw new IllegalArgumentException("at least one spec");
        this.specs = List.copyOf(specs);
        this.clock = clock;
        Duration longest = specs.stream().map(RateLimitSpec::period).max(Duration::compareTo).orElseThrow();
        this.buckets = Caffeine.newBuilder().expireAfterAccess(longest).maximumSize(200_000).build();
    }

    public RateLimitDecision tryAcquire(String key) {
        Buckets b = buckets.get(key, k -> new Buckets(specs, clock.millis()));
        return b.tryAcquire(clock.millis());
    }

    /** Forget a key (used when an account is deleted). */
    public void evict(String key) {
        buckets.invalidate(key);
    }

    private static final class Buckets {
        private final List<RateLimitSpec> specs;
        private final double[] tokens;
        private long lastRefill;

        Buckets(List<RateLimitSpec> specs, long now) {
            this.specs = specs;
            this.tokens = new double[specs.size()];
            for (int i = 0; i < tokens.length; i++) tokens[i] = specs.get(i).permits();
            this.lastRefill = now;
        }

        synchronized RateLimitDecision tryAcquire(long now) {
            long elapsed = Math.max(0, now - lastRefill);
            lastRefill = now;
            long worstWaitMillis = 0;
            for (int i = 0; i < tokens.length; i++) {
                RateLimitSpec s = specs.get(i);
                double perMilli = (double) s.permits() / s.period().toMillis();
                tokens[i] = Math.min(s.permits(), tokens[i] + elapsed * perMilli);
                if (tokens[i] < 1.0) {
                    worstWaitMillis = Math.max(worstWaitMillis, (long) Math.ceil((1.0 - tokens[i]) / perMilli));
                }
            }
            if (worstWaitMillis > 0) {
                return RateLimitDecision.rejected((worstWaitMillis + 999) / 1000);
            }
            for (int i = 0; i < tokens.length; i++) tokens[i] -= 1.0;
            return RateLimitDecision.ALLOWED;
        }
    }
}
