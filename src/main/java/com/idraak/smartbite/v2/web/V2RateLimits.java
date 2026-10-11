package com.idraak.smartbite.v2.web;

import com.idraak.smartbite.common.ratelimit.RateLimitDecision;
import com.idraak.smartbite.common.ratelimit.RateLimitSpec;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Per-uid burst limits for each v2 route (in memory, per instance). */
public class V2RateLimits {

    public enum Route { SUGGESTIONS, RECIPES, REPORTS, QUOTA, DELETE_ME }

    private final Map<Route, RateLimiter> limiters = new EnumMap<>(Route.class);

    public V2RateLimits(V2Properties.Limits l, Clock clock) {
        limiters.put(Route.SUGGESTIONS, new RateLimiter(List.of(
                RateLimitSpec.perMinute(l.suggestionsPerMinute()), RateLimitSpec.perHour(l.suggestionsPerHour())), clock));
        limiters.put(Route.RECIPES, new RateLimiter(List.of(
                RateLimitSpec.perMinute(l.recipesPerMinute()), RateLimitSpec.perHour(l.recipesPerHour())), clock));
        limiters.put(Route.REPORTS, new RateLimiter(List.of(
                RateLimitSpec.perHour(l.reportsPerHour()), RateLimitSpec.perDay(l.reportsPerDay())), clock));
        limiters.put(Route.QUOTA, new RateLimiter(List.of(RateLimitSpec.perMinute(l.quotaPerMinute())), clock));
        limiters.put(Route.DELETE_ME, new RateLimiter(List.of(RateLimitSpec.perHour(l.deletePerHour())), clock));
    }

    /** @throws ApiException RATE_LIMITED with Retry-After */
    public void check(Route route, String uid) {
        RateLimitDecision decision = limiters.get(route).tryAcquire(uid);
        if (!decision.allowed()) throw ApiException.rateLimited(decision.retryAfterSeconds());
    }

    public void evict(String uid) {
        limiters.values().forEach(l -> l.evict(uid));
    }
}
