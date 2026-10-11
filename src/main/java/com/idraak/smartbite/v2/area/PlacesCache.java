package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.suggestion.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Caches only what Google's terms allow (verified 2026-10-11): place ids (storable indefinitely, Places policies) and
 * lat/lng (up to 30 consecutive days, Service Specific Terms §14.3). TTL defaults to 7 days and is enforced on read
 * and by {@link PlacesCacheCleanup}, which hard-deletes expired documents.
 */
public class PlacesCache {

    static final String CELLS = "places_cells";
    static final String TEXT = "places_text";
    static final String EXPIRE_AT = "expire_at";

    private final DocumentStore store;
    private final Duration ttl;
    private final Clock clock;

    public PlacesCache(DocumentStore store, Duration ttl, Clock clock) {
        if (ttl.toDays() > 30) throw new IllegalArgumentException("Places cache TTL must be <= 30 days");
        this.store = store;
        this.ttl = ttl;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public Optional<List<PlacesClient.Place>> cell(String cellKey) {
        return store.get(CELLS, cellKey).filter(this::fresh).map(doc -> {
            List<PlacesClient.Place> out = new ArrayList<>();
            if (doc.get("places") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && m.get("id") instanceof String id
                            && m.get("lat") instanceof Number lat && m.get("lng") instanceof Number lng) {
                        out.add(new PlacesClient.Place(id, lat.doubleValue(), lng.doubleValue()));
                    }
                }
            }
            return out;
        });
    }

    public void putCell(String cellKey, List<PlacesClient.Place> places) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlacesClient.Place p : places) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", p.id());
            row.put("lat", p.lat());
            row.put("lng", p.lng());
            rows.add(row);
        }
        Instant now = clock.instant();
        store.set(CELLS, cellKey, Map.of("places", rows, "fetched_at", now, EXPIRE_AT, now.plus(ttl)));
    }

    public Optional<List<String>> text(String dish, String cellKey) {
        return store.get(TEXT, textKey(dish, cellKey)).filter(this::fresh).map(doc -> {
            List<String> ids = new ArrayList<>();
            if (doc.get("place_ids") instanceof List<?> l) l.forEach(o -> ids.add(String.valueOf(o)));
            return ids;
        });
    }

    public void putText(String dish, String cellKey, List<String> placeIds) {
        Instant now = clock.instant();
        store.set(TEXT, textKey(dish, cellKey), Map.of("place_ids", List.copyOf(placeIds), "fetched_at", now, EXPIRE_AT, now.plus(ttl)));
    }

    private boolean fresh(Map<String, Object> doc) {
        return doc.get(EXPIRE_AT) instanceof Instant exp && exp.isAfter(clock.instant());
    }

    static String textKey(String dish, String cellKey) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((Text.norm(dish) + "|" + cellKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
