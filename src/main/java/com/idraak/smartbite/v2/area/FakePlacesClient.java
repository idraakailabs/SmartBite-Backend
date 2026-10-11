package com.idraak.smartbite.v2.area;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Places fake ({@code PLACES_PROVIDER=fake}) for tests and for staging before the owner's key exists: no network,
 * no cost. Returns nothing unless configured, so eat-out picks end up "popular around" with {@code no_match_found}.
 */
public class FakePlacesClient implements PlacesClient {

    private final List<Place> nearby = new ArrayList<>();
    private final Map<String, List<String>> textResults = new ConcurrentHashMap<>();
    private final AtomicInteger nearbyCalls = new AtomicInteger();
    private final AtomicInteger textCalls = new AtomicInteger();
    private volatile PlacesException failure;

    public synchronized void setNearby(List<Place> places) {
        nearby.clear();
        nearby.addAll(places);
    }

    /** Text search results by dish name (matched case-insensitively). */
    public void setTextResults(String dish, List<String> ids) {
        textResults.put(dish.toLowerCase(), List.copyOf(ids));
    }

    public void failWith(PlacesException e) {
        this.failure = e;
    }

    public synchronized void reset() {
        nearby.clear();
        textResults.clear();
        nearbyCalls.set(0);
        textCalls.set(0);
        failure = null;
    }

    public int nearbyCalls() {
        return nearbyCalls.get();
    }

    public int textCalls() {
        return textCalls.get();
    }

    @Override
    public synchronized List<Place> nearby(double lat, double lng, double radiusKm, List<String> includedTypes) {
        nearbyCalls.incrementAndGet();
        if (failure != null) throw failure;
        return List.copyOf(nearby);
    }

    @Override
    public List<String> textSearchIds(String query, double lat, double lng, double radiusKm) {
        textCalls.incrementAndGet();
        if (failure != null) throw failure;
        return textResults.getOrDefault(query.toLowerCase(), List.of());
    }
}
