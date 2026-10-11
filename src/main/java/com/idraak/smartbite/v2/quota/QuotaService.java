package com.idraak.smartbite.v2.quota;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.settings.AppSettings;
import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.DocumentStore.TxResult;
import com.idraak.smartbite.v2.store.StoreUnavailableException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Free daily allowance (README §4). Day = server clock in the user's timezone; the timezone is pinned and may
 * change only once per {@code timezoneRepinAfter}. A slot is reserved before the AI call and committed only on
 * a successful non-empty set, all inside store transactions, so concurrent requests can't exceed the limit.
 */
public class QuotaService {

    static final String COLLECTION = "quota";

    /** Result of a reservation. {@code alreadyCommitted}: this idempotency key was already counted. */
    public record Reservation(String key, String day, String timezone, boolean alreadyCommitted) {}

    private final DocumentStore store;
    private final AppSettings settings;
    private final V2Properties.Quota cfg;
    private final Clock clock;

    public QuotaService(DocumentStore store, AppSettings settings, V2Properties.Quota cfg, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.cfg = cfg;
        this.clock = clock;
    }

    /** Read-only view; never changes the counter or the pin. */
    public QuotaSnapshot peek(String uid, ZoneId requested) {
        Instant now = clock.instant();
        Optional<QuotaDoc> doc = guard(() -> store.get(COLLECTION, uid)).map(QuotaDoc::fromMap);
        ZoneId zone = effectiveZone(doc.orElse(null), requested, now);
        LocalDate today = LocalDate.ofInstant(now, zone);
        int used = doc.filter(d -> today.toString().equals(d.day())).map(QuotaDoc::used).orElse(0);
        return QuotaSnapshot.free(settings.freeDailySuggestions(), used, today, zone.getId(), resetAt(today, zone));
    }

    public QuotaSnapshot premiumSnapshot(ZoneId zone) {
        return QuotaSnapshot.premium(LocalDate.ofInstant(clock.instant(), zone), zone.getId());
    }

    /** @throws ApiException DAILY_LIMIT_REACHED (429) or PROVIDER_UNAVAILABLE when the store is down */
    public Reservation reserve(String uid, ZoneId requested, String idempotencyKey) {
        int limit = settings.freeDailySuggestions();
        return guard(() -> store.transaction(COLLECTION, uid, current -> {
            Instant now = clock.instant();
            QuotaDoc doc = rollOver(current.map(QuotaDoc::fromMap).orElse(null), requested, now);
            if (idempotencyKey != null && doc.committedKeys().contains(idempotencyKey)) {
                return TxResult.unchanged(new Reservation(idempotencyKey, doc.day(), doc.timezone(), true));
            }
            Map<String, Instant> active = new LinkedHashMap<>(doc.reservations());
            active.values().removeIf(exp -> !exp.isAfter(now));
            if (idempotencyKey != null && active.containsKey(idempotencyKey)) {
                // The same action is still in flight; don't hold a second slot for it.
                active.remove(idempotencyKey);
            }
            if (doc.used() + active.size() >= limit) {
                ZoneId zone = ZoneId.of(doc.timezone());
                Instant reset = resetAt(LocalDate.parse(doc.day()), zone);
                throw dailyLimit(limit, doc.used(), reset, now);
            }
            String key = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();
            active.put(key, now.plus(cfg.reservationTtl()));
            QuotaDoc next = new QuotaDoc(doc.day(), doc.timezone(), doc.timezoneSetAt(), doc.used(), active, doc.committedKeys());
            return TxResult.write(next.toMap(now), new Reservation(key, doc.day(), doc.timezone(), false));
        }));
    }

    /** Counts the reserved set. Returns the snapshot to put in the response. */
    public QuotaSnapshot commit(String uid, Reservation r) {
        int limit = settings.freeDailySuggestions();
        if (r.alreadyCommitted()) {
            return peek(uid, ZoneId.of(r.timezone()));
        }
        return guard(() -> store.transaction(COLLECTION, uid, current -> {
            Instant now = clock.instant();
            QuotaDoc doc = current.map(QuotaDoc::fromMap).orElse(null);
            if (doc == null) {
                doc = new QuotaDoc(r.day(), r.timezone(), now, 0, Map.of(), List.of());
            }
            Map<String, Instant> active = new LinkedHashMap<>(doc.reservations());
            active.remove(r.key());
            int used = doc.used();
            boolean counted = doc.committedKeys().contains(r.key());
            if (!counted && r.day().equals(doc.day())) used++;
            List<String> keys = new ArrayList<>(doc.committedKeys());
            keys.remove(r.key());
            keys.add(r.key());
            while (keys.size() > QuotaDoc.MAX_COMMITTED_KEYS) keys.remove(0);
            QuotaDoc next = new QuotaDoc(doc.day(), doc.timezone(), doc.timezoneSetAt(), used, active, keys);
            ZoneId zone = ZoneId.of(next.timezone());
            LocalDate day = LocalDate.parse(next.day());
            return TxResult.write(next.toMap(now), QuotaSnapshot.free(limit, used, day, zone.getId(), resetAt(day, zone)));
        }));
    }

    /** Frees the reserved slot after a failure. Best effort: an unreleased slot expires anyway. */
    public void release(String uid, Reservation r) {
        if (r.alreadyCommitted()) return;
        try {
            store.transaction(COLLECTION, uid, current -> {
                if (current.isEmpty()) return TxResult.unchanged(null);
                QuotaDoc doc = QuotaDoc.fromMap(current.get());
                Map<String, Instant> active = new LinkedHashMap<>(doc.reservations());
                if (active.remove(r.key()) == null) return TxResult.unchanged(null);
                QuotaDoc next = new QuotaDoc(doc.day(), doc.timezone(), doc.timezoneSetAt(), doc.used(), active, doc.committedKeys());
                return TxResult.write(next.toMap(clock.instant()), null);
            });
        } catch (StoreUnavailableException ignored) {
            // The reservation expires after reservation-ttl.
        }
    }

    /** @return true if a counter existed */
    public boolean delete(String uid) {
        return guard(() -> store.delete(COLLECTION, uid));
    }

    private QuotaDoc rollOver(QuotaDoc doc, ZoneId requested, Instant now) {
        ZoneId zone = effectiveZone(doc, requested, now);
        Instant setAt = doc == null || !zone.getId().equals(doc.timezone()) ? now : doc.timezoneSetAt();
        String today = LocalDate.ofInstant(now, zone).toString();
        if (doc == null || !today.equals(doc.day())) {
            List<String> keys = doc == null ? List.of() : doc.committedKeys();
            return new QuotaDoc(today, zone.getId(), setAt, 0, Map.of(), keys);
        }
        return new QuotaDoc(doc.day(), zone.getId(), setAt, doc.used(), doc.reservations(), doc.committedKeys());
    }

    /** The pinned zone, unless none is pinned or the pin is older than the re-pin window. */
    private ZoneId effectiveZone(QuotaDoc doc, ZoneId requested, Instant now) {
        if (doc == null || doc.timezone() == null) return requested;
        if (doc.timezone().equals(requested.getId())) return requested;
        Instant setAt = doc.timezoneSetAt();
        if (setAt == null || Duration.between(setAt, now).compareTo(cfg.timezoneRepinAfter()) >= 0) return requested;
        try {
            return ZoneId.of(doc.timezone());
        } catch (RuntimeException e) {
            return requested;
        }
    }

    static Instant resetAt(LocalDate day, ZoneId zone) {
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static ApiException dailyLimit(int limit, int used, Instant reset, Instant now) {
        long retryAfter = Math.max(1, Duration.between(now, reset).toSeconds());
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("limit", limit);
        ext.put("used", used);
        ext.put("reset_at", reset.toString());
        ext.put("retry_after_seconds", retryAfter);
        return new ApiException(ErrorCode.DAILY_LIMIT_REACHED, null, ext, retryAfter, null);
    }

    private static <T> T guard(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (StoreUnavailableException e) {
            throw ApiException.providerUnavailable(10, e);
        }
    }
}
