package com.idraak.smartbite.v2.quota;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code quota/{uid}}: day, pinned timezone, used count, active reservations (key -> expiry) and the last
 * committed idempotency keys. Pure value; QuotaService applies the rules inside a store transaction.
 */
record QuotaDoc(String day, String timezone, Instant timezoneSetAt, int used,
                Map<String, Instant> reservations, List<String> committedKeys) {

    static final int MAX_COMMITTED_KEYS = 10;

    Map<String, Object> toMap(Instant now) {
        Map<String, Object> m = new HashMap<>();
        m.put("day", day);
        m.put("timezone", timezone);
        m.put("timezone_set_at", timezoneSetAt);
        m.put("used", (long) used);
        m.put("reservations", new HashMap<>(reservations));
        m.put("committed_keys", new ArrayList<>(committedKeys));
        m.put("updated_at", now);
        return m;
    }

    @SuppressWarnings("unchecked")
    static QuotaDoc fromMap(Map<String, Object> m) {
        Map<String, Instant> reservations = new LinkedHashMap<>();
        if (m.get("reservations") instanceof Map<?, ?> r) {
            r.forEach((k, v) -> {
                if (v instanceof Instant i) reservations.put((String) k, i);
            });
        }
        List<String> keys = new ArrayList<>();
        if (m.get("committed_keys") instanceof List<?> l) l.forEach(k -> keys.add(String.valueOf(k)));
        return new QuotaDoc(
                (String) m.get("day"),
                (String) m.get("timezone"),
                m.get("timezone_set_at") instanceof Instant i ? i : null,
                m.get("used") instanceof Number n ? n.intValue() : 0,
                reservations, keys);
    }
}
