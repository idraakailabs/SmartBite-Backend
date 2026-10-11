package com.idraak.smartbite.v2.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The small slice of Firestore the backend uses (ADR-0001). Collection names are logical; implementations add
 * the environment prefix. Values are String, Long, Double, Boolean, Instant, List and Map. Implementations:
 * {@link FirestoreDocumentStore} (Admin SDK) and {@link InMemoryDocumentStore} (tests and local runs).
 *
 * All methods throw {@link StoreUnavailableException} when the store can't be reached in time.
 */
public interface DocumentStore {

    /** Marker for {@link #updateFields}: remove this field. */
    Object DELETE_FIELD = new Object() {
        @Override
        public String toString() {
            return "DELETE_FIELD";
        }
    };

    Optional<Map<String, Object>> get(String collection, String id);

    void set(String collection, String id, Map<String, Object> data);

    /** Creates the document only if absent. @return false if it already existed */
    boolean create(String collection, String id, Map<String, Object> data);

    /** @return true if a document was deleted */
    boolean delete(String collection, String id);

    void updateFields(String collection, String id, Map<String, Object> fields);

    /** Ids of documents whose {@code field} equals {@code value} (at most {@code limit}). */
    List<String> findIds(String collection, String field, Object value, int limit);

    /** Deletes documents whose Instant field {@code expireField} is before {@code now}. @return deleted count */
    int deleteExpired(String collection, String expireField, Instant now, int limit);

    /** Atomically adds {@code delta} to a numeric field (creating the document if needed) and returns the new value. */
    long increment(String collection, String id, String field, long delta);

    /**
     * Read-modify-write in one transaction. The function may run more than once (Firestore retries on contention),
     * so it must have no side effects. Exceptions thrown by the function propagate unchanged.
     */
    <T> T transaction(String collection, String id, Function<Optional<Map<String, Object>>, TxResult<T>> fn);

    /** What a transaction does to the document, and what it returns. */
    record TxResult<T>(Map<String, Object> newData, boolean delete, T result) {
        public static <T> TxResult<T> write(Map<String, Object> data, T result) {
            return new TxResult<>(data, false, result);
        }

        public static <T> TxResult<T> unchanged(T result) {
            return new TxResult<>(null, false, result);
        }

        public static <T> TxResult<T> deleted(T result) {
            return new TxResult<>(null, true, result);
        }
    }
}
