package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.common.ratelimit.RateLimitSpec;
import com.idraak.smartbite.common.ratelimit.RateLimiter;
import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.model.Enums.UnverifiedReason;
import com.idraak.smartbite.v2.store.DocumentStore;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Spend controls: per-uid daily limits (in memory) and global daily caps per UTC day (durable, in the store, so
 * restarts can't reset them). The default Nearby cap of 150/day keeps a 31-day month under the 5,000 free calls.
 */
public class PlacesBudget {

    static final String USAGE = "places_usage";

    public enum Call { NEARBY, TEXT }

    private final DocumentStore store;
    private final V2Properties.Places cfg;
    private final Clock clock;
    private final RateLimiter nearbyPerUid;
    private final RateLimiter textPerUid;

    public PlacesBudget(DocumentStore store, V2Properties.Places cfg, Clock clock) {
        this.store = store;
        this.cfg = cfg;
        this.clock = clock;
        this.nearbyPerUid = new RateLimiter(List.of(RateLimitSpec.perDay(Math.max(1, cfg.nearbyPerUidDaily()))), clock);
        this.textPerUid = new RateLimiter(List.of(RateLimitSpec.perDay(Math.max(1, cfg.textPerUidDaily()))), clock);
    }

    /** @return null if the call may proceed, else the reason it may not */
    public UnverifiedReason acquire(Call call, String uid) {
        RateLimiter perUid = call == Call.NEARBY ? nearbyPerUid : textPerUid;
        if (!perUid.tryAcquire(uid).allowed()) return UnverifiedReason.USER_LIMIT_REACHED;
        int cap = call == Call.NEARBY ? cfg.nearbyGlobalDailyCap() : cfg.textGlobalDailyCap();
        String day = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        long used = store.increment(USAGE, day, call == Call.NEARBY ? "nearby_calls" : "text_calls", 1);
        return used > cap ? UnverifiedReason.GLOBAL_CAP_REACHED : null;
    }

    public void evict(String uid) {
        nearbyPerUid.evict(uid);
        textPerUid.evict(uid);
    }
}
