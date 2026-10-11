package com.idraak.smartbite.v2.entitlement;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.idraak.smartbite.v2.config.V2Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Premium check with a short-TTL cache (README §5): Premium cached 5 min, free 60 s; {@code forceRefresh} (after a
 * purchase) bypasses the cache at most once per 30 s per uid; on provider failure the last value up to 24 h old is
 * used, otherwise the user is treated as free. A client-supplied Premium flag is never trusted.
 */
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private record Entry(boolean premium, Instant fetchedAt) {}

    private final EntitlementSource source;
    private final V2Properties.Entitlement cfg;
    private final Clock clock;
    private final Cache<String, Entry> cache;
    private final Cache<String, Instant> lastForcedRefresh;

    public EntitlementService(EntitlementSource source, V2Properties.Entitlement cfg, Clock clock) {
        this.source = source;
        this.cfg = cfg;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(cfg.staleIfError()).maximumSize(100_000).build();
        this.lastForcedRefresh = Caffeine.newBuilder().expireAfterWrite(cfg.refreshMinInterval()).maximumSize(100_000).build();
    }

    public boolean isPremium(String uid, boolean forceRefresh) {
        Instant now = clock.instant();
        Entry cached = cache.getIfPresent(uid);
        boolean refreshAllowed = forceRefresh && lastForcedRefresh.getIfPresent(uid) == null;
        if (cached != null && !refreshAllowed) {
            Duration ttl = cached.premium() ? cfg.ttlPremium() : cfg.ttlFree();
            if (Duration.between(cached.fetchedAt(), now).compareTo(ttl) < 0) return cached.premium();
        }
        if (refreshAllowed) lastForcedRefresh.put(uid, now);
        try {
            boolean premium = source.isPremium(uid);
            cache.put(uid, new Entry(premium, now));
            return premium;
        } catch (EntitlementUnavailableException e) {
            if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(cfg.staleIfError()) <= 0) {
                log.warn("entitlement source unavailable; using last known value");
                return cached.premium();
            }
            log.warn("entitlement source unavailable and nothing cached; treating as free");
            return false;
        }
    }

    public void evict(String uid) {
        cache.invalidate(uid);
        lastForcedRefresh.invalidate(uid);
    }
}
