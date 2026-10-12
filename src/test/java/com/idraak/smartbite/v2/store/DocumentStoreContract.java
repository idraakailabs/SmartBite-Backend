package com.idraak.smartbite.v2.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour every {@link DocumentStore} must have. Subclasses supply the store: the in-memory one always runs;
 * the Firestore one runs against the emulator ({@link FirestoreDocumentStoreEmulatorTest}), so the transaction
 * and increment code used for quotas and caches is tested on real Firestore semantics.
 */
abstract class DocumentStoreContract {

    abstract DocumentStore store();

    /** Parallel writers to one document in the concurrency tests. */
    static final int CONCURRENCY = 3;

    /** A collection name no other test touches, so runs against a shared emulator don't interfere. */
    final String collection = "contract_" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void setThenGetRoundTripsSupportedTypes() {
        Instant when = Instant.parse("2026-10-12T06:00:00Z");
        store().set(collection, "a", Map.of(
                "text", "Chicken Karahi", "count", 3L, "ratio", 0.5, "flag", true, "at", when,
                "list", List.of("a", "b"), "map", Map.of("k", "v")));

        Map<String, Object> got = store().get(collection, "a").orElseThrow();

        assertThat(got.get("text")).isEqualTo("Chicken Karahi");
        assertThat(((Number) got.get("count")).longValue()).isEqualTo(3L);
        assertThat(((Number) got.get("ratio")).doubleValue()).isEqualTo(0.5);
        assertThat(got.get("flag")).isEqualTo(true);
        assertThat(got.get("at")).isEqualTo(when);
        assertThat(got.get("list")).isEqualTo(List.of("a", "b"));
        assertThat(got.get("map")).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void getOfAMissingDocumentIsEmpty() {
        assertThat(store().get(collection, "missing")).isEmpty();
    }

    @Test
    void createOnlyWritesWhenAbsent() {
        assertThat(store().create(collection, "c", Map.of("v", 1L))).isTrue();
        assertThat(store().create(collection, "c", Map.of("v", 2L))).isFalse();
        assertThat(((Number) store().get(collection, "c").orElseThrow().get("v")).longValue()).isEqualTo(1L);
    }

    @Test
    void deleteReportsWhetherSomethingWasDeleted() {
        store().set(collection, "d", Map.of("v", 1L));
        assertThat(store().delete(collection, "d")).isTrue();
        assertThat(store().get(collection, "d")).isEmpty();
        assertThat(store().delete(collection, "d")).isFalse();
    }

    @Test
    void updateFieldsMergesAndRemovesFields() {
        store().set(collection, "u", Map.of("keep", "x", "drop", "y"));
        Map<String, Object> change = new HashMap<>();
        change.put("drop", DocumentStore.DELETE_FIELD);
        change.put("added", 5L);

        store().updateFields(collection, "u", change);

        Map<String, Object> got = store().get(collection, "u").orElseThrow();
        assertThat(got).containsKeys("keep", "added").doesNotContainKey("drop");
    }

    @Test
    void findIdsMatchesAFieldValue() {
        store().set(collection, "p1", Map.of("status", "pending"));
        store().set(collection, "p2", Map.of("status", "pending"));
        store().set(collection, "done", Map.of("status", "completed"));

        assertThat(store().findIds(collection, "status", "pending", 10)).containsExactlyInAnyOrder("p1", "p2");
        assertThat(store().findIds(collection, "status", "pending", 1)).hasSize(1);
    }

    @Test
    void deleteExpiredRemovesOnlyPastDocuments() {
        Instant now = Instant.parse("2026-10-12T06:00:00Z");
        store().set(collection, "old", Map.of("expire_at", now.minus(1, ChronoUnit.HOURS)));
        store().set(collection, "fresh", Map.of("expire_at", now.plus(1, ChronoUnit.HOURS)));

        assertThat(store().deleteExpired(collection, "expire_at", now, 10)).isEqualTo(1);
        assertThat(store().get(collection, "old")).isEmpty();
        assertThat(store().get(collection, "fresh")).isPresent();
    }

    @Test
    void incrementCreatesAndAddsAtomically() throws Exception {
        assertThat(store().increment(collection, "counter", "n", 1)).isEqualTo(1L);

        // Concurrent increments, as when one eat-out request runs its text searches against the daily Places
        // counter. Kept small: the emulator serialises contended transactions far more slowly than Firestore.
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 2 * CONCURRENCY; i++) jobs.add(() -> store().increment(collection, "counter", "n", 1));
            for (Future<Long> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(((Number) store().get(collection, "counter").orElseThrow().get("n")).longValue()).isEqualTo(1L + 2 * CONCURRENCY);
    }

    @Test
    void transactionWritesWhatTheFunctionReturns() {
        String result = store().transaction(collection, "t", current -> {
            assertThat(current).isEmpty();
            return DocumentStore.TxResult.write(Map.of("used", 1L), "created");
        });
        assertThat(result).isEqualTo("created");

        Long next = store().transaction(collection, "t", current -> {
            long used = ((Number) current.orElseThrow().get("used")).longValue();
            return DocumentStore.TxResult.write(Map.of("used", used + 1), used + 1);
        });
        assertThat(next).isEqualTo(2L);
        assertThat(((Number) store().get(collection, "t").orElseThrow().get("used")).longValue()).isEqualTo(2L);
    }

    @Test
    void transactionCanLeaveTheDocumentUnchanged() {
        store().set(collection, "same", Map.of("v", 7L));
        String result = store().transaction(collection, "same", current -> DocumentStore.TxResult.unchanged("kept"));
        assertThat(result).isEqualTo("kept");
        assertThat(((Number) store().get(collection, "same").orElseThrow().get("v")).longValue()).isEqualTo(7L);
    }

    @Test
    void concurrentTransactionsDoNotLoseUpdates() throws Exception {
        store().set(collection, "quota", Map.of("used", 0L));
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 2 * CONCURRENCY; i++) {
                jobs.add(() -> store().transaction(collection, "quota", current -> {
                    long used = ((Number) current.orElseThrow().get("used")).longValue();
                    return DocumentStore.TxResult.write(Map.of("used", used + 1), used + 1);
                }));
            }
            for (Future<Long> f : pool.invokeAll(jobs)) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(((Number) store().get(collection, "quota").orElseThrow().get("used")).longValue()).isEqualTo(2L * CONCURRENCY);
    }
}
