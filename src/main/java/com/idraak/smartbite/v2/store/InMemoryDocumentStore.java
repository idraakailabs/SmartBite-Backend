package com.idraak.smartbite.v2.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * In-process store for tests and local runs. Every operation is serialised on one lock, which gives the same
 * atomicity guarantees as Firestore transactions. Refused in staging/production by StartupGuard.
 */
public class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, Map<String, Object>> docs = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    /** Test hook: make every call fail as if Firestore were down. */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    private void checkAvailable() {
        if (unavailable) throw new StoreUnavailableException("in-memory store set unavailable", null);
    }

    private static String key(String collection, String id) {
        return collection + "/" + id;
    }

    private static Map<String, Object> copy(Map<String, Object> data) {
        return new HashMap<>(data);
    }

    @Override
    public synchronized Optional<Map<String, Object>> get(String collection, String id) {
        checkAvailable();
        Map<String, Object> d = docs.get(key(collection, id));
        return Optional.ofNullable(d).map(InMemoryDocumentStore::copy);
    }

    @Override
    public synchronized void set(String collection, String id, Map<String, Object> data) {
        checkAvailable();
        docs.put(key(collection, id), copy(data));
    }

    @Override
    public synchronized boolean create(String collection, String id, Map<String, Object> data) {
        checkAvailable();
        return docs.putIfAbsent(key(collection, id), copy(data)) == null;
    }

    @Override
    public synchronized boolean delete(String collection, String id) {
        checkAvailable();
        return docs.remove(key(collection, id)) != null;
    }

    @Override
    public synchronized void updateFields(String collection, String id, Map<String, Object> fields) {
        checkAvailable();
        Map<String, Object> d = docs.get(key(collection, id));
        if (d == null) throw new IllegalStateException("document not found: " + collection);
        fields.forEach((k, v) -> {
            if (v == DELETE_FIELD) d.remove(k);
            else d.put(k, v);
        });
    }

    @Override
    public synchronized List<String> findIds(String collection, String field, Object value, int limit) {
        checkAvailable();
        List<String> ids = new ArrayList<>();
        String prefix = collection + "/";
        for (var e : docs.entrySet()) {
            if (ids.size() >= limit) break;
            if (e.getKey().startsWith(prefix) && Objects.equals(e.getValue().get(field), value)) {
                ids.add(e.getKey().substring(prefix.length()));
            }
        }
        return ids;
    }

    @Override
    public synchronized int deleteExpired(String collection, String expireField, Instant now, int limit) {
        checkAvailable();
        String prefix = collection + "/";
        List<String> expired = new ArrayList<>();
        for (var e : docs.entrySet()) {
            if (expired.size() >= limit) break;
            if (e.getKey().startsWith(prefix) && e.getValue().get(expireField) instanceof Instant t && t.isBefore(now)) {
                expired.add(e.getKey());
            }
        }
        expired.forEach(docs::remove);
        return expired.size();
    }

    @Override
    public synchronized long increment(String collection, String id, String field, long delta) {
        checkAvailable();
        Map<String, Object> d = docs.computeIfAbsent(key(collection, id), k -> new HashMap<>());
        long next = (d.get(field) instanceof Number n ? n.longValue() : 0L) + delta;
        d.put(field, next);
        return next;
    }

    @Override
    public synchronized <T> T transaction(String collection, String id, Function<Optional<Map<String, Object>>, TxResult<T>> fn) {
        checkAvailable();
        String k = key(collection, id);
        Map<String, Object> current = docs.get(k);
        TxResult<T> r = fn.apply(Optional.ofNullable(current).map(InMemoryDocumentStore::copy));
        if (r.delete()) docs.remove(k);
        else if (r.newData() != null) docs.put(k, copy(r.newData()));
        return r.result();
    }

    /** Test helper. */
    public synchronized int count(String collection) {
        String prefix = collection + "/";
        return (int) docs.keySet().stream().filter(s -> s.startsWith(prefix)).count();
    }
}
