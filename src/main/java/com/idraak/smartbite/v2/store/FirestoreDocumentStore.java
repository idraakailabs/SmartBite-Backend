package com.idraak.smartbite.v2.store;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Firestore through the Admin SDK. Instants are stored as Firestore timestamps (so a TTL policy can use the
 * {@code expire_at} fields). Every call has a deadline.
 *
 * Not covered by automated tests: the Firestore emulator needs the Firebase CLI or Docker, neither of which is
 * installed on the development machine. Verify against staging (see docs/ops/ENVIRONMENT.md).
 */
public class FirestoreDocumentStore implements DocumentStore {

    private final Firestore db;
    private final String prefix;
    private final long timeoutMs;

    public FirestoreDocumentStore(Firestore db, String prefix, Duration timeout) {
        this.db = db;
        this.prefix = prefix == null ? "" : prefix;
        this.timeoutMs = timeout.toMillis();
    }

    private DocumentReference ref(String collection, String id) {
        return db.collection(prefix + collection).document(id);
    }

    private <T> T await(Future<T> f, String op) {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(op + " interrupted", e);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new StoreUnavailableException(op + " timed out", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re && !(re instanceof ApiException)) throw re;
            throw new StoreUnavailableException(op + " failed", e.getCause());
        }
    }

    @Override
    public Optional<Map<String, Object>> get(String collection, String id) {
        DocumentSnapshot s = await(ref(collection, id).get(), "get");
        return s.exists() ? Optional.of(fromFirestore(s.getData())) : Optional.empty();
    }

    @Override
    public void set(String collection, String id, Map<String, Object> data) {
        await(ref(collection, id).set(toFirestore(data)), "set");
    }

    @Override
    public boolean create(String collection, String id, Map<String, Object> data) {
        try {
            ref(collection, id).create(toFirestore(data)).get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException api && api.getStatusCode().getCode() == StatusCode.Code.ALREADY_EXISTS) {
                return false;
            }
            throw new StoreUnavailableException("create failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("create interrupted", e);
        } catch (TimeoutException e) {
            throw new StoreUnavailableException("create timed out", e);
        }
    }

    @Override
    public boolean delete(String collection, String id) {
        boolean existed = await(ref(collection, id).get(), "get").exists();
        await(ref(collection, id).delete(), "delete");
        return existed;
    }

    @Override
    public void updateFields(String collection, String id, Map<String, Object> fields) {
        Map<String, Object> updates = new HashMap<>();
        fields.forEach((k, v) -> updates.put(k, v == DELETE_FIELD ? FieldValue.delete() : toFirestoreValue(v)));
        await(ref(collection, id).update(updates), "update");
    }

    @Override
    public List<String> findIds(String collection, String field, Object value, int limit) {
        List<String> ids = new ArrayList<>();
        for (QueryDocumentSnapshot d : await(db.collection(prefix + collection).whereEqualTo(field, toFirestoreValue(value))
                .limit(limit).get(), "query").getDocuments()) {
            ids.add(d.getId());
        }
        return ids;
    }

    @Override
    public int deleteExpired(String collection, String expireField, Instant now, int limit) {
        List<QueryDocumentSnapshot> docs = await(db.collection(prefix + collection)
                .whereLessThan(expireField, toTimestamp(now)).limit(limit).get(), "query").getDocuments();
        if (docs.isEmpty()) return 0;
        WriteBatch batch = db.batch();
        docs.forEach(d -> batch.delete(d.getReference()));
        await(batch.commit(), "batch delete");
        return docs.size();
    }

    @Override
    public long increment(String collection, String id, String field, long delta) {
        return transaction(collection, id, current -> {
            long next = current.map(m -> m.get(field) instanceof Number n ? n.longValue() : 0L).orElse(0L) + delta;
            Map<String, Object> data = new HashMap<>(current.orElse(Map.of()));
            data.put(field, next);
            return TxResult.write(data, next);
        });
    }

    @Override
    public <T> T transaction(String collection, String id, Function<Optional<Map<String, Object>>, TxResult<T>> fn) {
        DocumentReference ref = ref(collection, id);
        return await(db.runTransaction(tx -> {
            DocumentSnapshot s = tx.get(ref).get();
            TxResult<T> r = fn.apply(s.exists() ? Optional.of(fromFirestore(s.getData())) : Optional.empty());
            if (r.delete()) tx.delete(ref);
            else if (r.newData() != null) tx.set(ref, toFirestore(r.newData()));
            return r.result();
        }), "transaction");
    }

    private static Map<String, Object> toFirestore(Map<String, Object> data) {
        Map<String, Object> out = new HashMap<>();
        data.forEach((k, v) -> out.put(k, toFirestoreValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object toFirestoreValue(Object v) {
        if (v instanceof Instant i) return toTimestamp(i);
        if (v instanceof Map<?, ?> m) return toFirestore((Map<String, Object>) m);
        if (v instanceof List<?> l) return l.stream().map(FirestoreDocumentStore::toFirestoreValue).toList();
        return v;
    }

    private static Timestamp toTimestamp(Instant i) {
        return Timestamp.ofTimeSecondsAndNanos(i.getEpochSecond(), i.getNano());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromFirestore(Map<String, Object> data) {
        Map<String, Object> out = new HashMap<>();
        if (data == null) return out;
        data.forEach((k, v) -> out.put(k, fromFirestoreValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object fromFirestoreValue(Object v) {
        if (v instanceof Timestamp t) return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
        if (v instanceof Date d) return d.toInstant();
        if (v instanceof Map<?, ?> m) return fromFirestore((Map<String, Object>) m);
        if (v instanceof List<?> l) return l.stream().map(FirestoreDocumentStore::fromFirestoreValue).toList();
        return v;
    }
}
