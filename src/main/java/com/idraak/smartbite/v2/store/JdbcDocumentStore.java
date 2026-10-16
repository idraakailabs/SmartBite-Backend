package com.idraak.smartbite.v2.store;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link DocumentStore} on our own PostgreSQL (D-024; {@code smartbite.v2.stores.mode=postgres}). One row per
 * document in {@code kv_document} (migration V2), the document as {@code jsonb}. Instants are written as
 * {@code {"$ts": "<ISO-8601>"}} and read back as Instants; integral numbers read back as Long, others as Double,
 * as with Firestore.
 *
 * <p>Transactions take a per-document advisory lock ({@code pg_advisory_xact_lock}), so read-modify-write on a
 * document that doesn't exist yet is serialised too. {@link #increment} is a single upsert. The connection's
 * {@code statement_timeout} bounds every call, including lock waits; database failures become
 * {@link StoreUnavailableException}, while exceptions from a transaction function propagate unchanged.
 */
public class JdbcDocumentStore implements DocumentStore {

    static final String TS = "$ts";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final String prefix;

    public JdbcDocumentStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, String prefix) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.prefix = prefix == null ? "" : prefix;
    }

    private String c(String collection) {
        return prefix + collection;
    }

    @Override
    public Optional<Map<String, Object>> get(String collection, String id) {
        return guard("get", () -> jdbc.query("select data::text from kv_document where collection = ? and id = ?",
                rs -> rs.next() ? Optional.of(decodeObject(rs.getString(1))) : Optional.<Map<String, Object>>empty(),
                c(collection), id));
    }

    @Override
    public void set(String collection, String id, Map<String, Object> data) {
        guard("set", () -> jdbc.update("""
                insert into kv_document (collection, id, data) values (?, ?, ?::jsonb)
                on conflict (collection, id) do update set data = excluded.data, updated_at = now()""",
                c(collection), id, encodeObject(data)));
    }

    @Override
    public boolean create(String collection, String id, Map<String, Object> data) {
        return guard("create", () -> jdbc.update("""
                insert into kv_document (collection, id, data) values (?, ?, ?::jsonb)
                on conflict (collection, id) do nothing""", c(collection), id, encodeObject(data)) == 1);
    }

    @Override
    public boolean delete(String collection, String id) {
        return guard("delete", () -> jdbc.update("delete from kv_document where collection = ? and id = ?", c(collection), id) == 1);
    }

    @Override
    public void updateFields(String collection, String id, Map<String, Object> fields) {
        guard("update", () -> tx.execute(status -> {
            lock(collection, id);
            Map<String, Object> current = jdbc.query("select data::text from kv_document where collection = ? and id = ?",
                    rs -> rs.next() ? decodeObject(rs.getString(1)) : null, c(collection), id);
            if (current == null) throw new IllegalStateException("document not found: " + collection);
            fields.forEach((k, v) -> {
                if (v == DELETE_FIELD) current.remove(k);
                else current.put(k, v);
            });
            jdbc.update("update kv_document set data = ?::jsonb, updated_at = now() where collection = ? and id = ?",
                    encodeObject(current), c(collection), id);
            return null;
        }));
    }

    @Override
    public List<String> findIds(String collection, String field, Object value, int limit) {
        return guard("query", () -> jdbc.queryForList(
                "select id from kv_document where collection = ? and data -> ? = ?::jsonb order by id limit ?",
                String.class, c(collection), field, mapper.writeValueAsString(encode(value)), limit));
    }

    @Override
    public int deleteExpired(String collection, String expireField, Instant now, int limit) {
        return guard("delete expired", () -> jdbc.update("""
                delete from kv_document where collection = ? and id in (
                    select id from kv_document
                    where collection = ? and (data -> ? ->> '$ts')::timestamptz < ?
                    limit ?)""", c(collection), c(collection), expireField, utc(now), limit));
    }

    @Override
    public long increment(String collection, String id, String field, long delta) {
        Long next = guard("increment", () -> jdbc.queryForObject("""
                insert into kv_document (collection, id, data) values (?, ?, jsonb_build_object(?::text, ?::bigint))
                on conflict (collection, id) do update set
                    data = kv_document.data || jsonb_build_object(?::text,
                           coalesce((kv_document.data ->> ?)::bigint, 0) + ?::bigint),
                    updated_at = now()
                returning (data ->> ?)::bigint""", Long.class,
                c(collection), id, field, delta, field, field, delta, field));
        return next == null ? 0 : next;
    }

    @Override
    public <T> T transaction(String collection, String id, Function<Optional<Map<String, Object>>, TxResult<T>> fn) {
        return guard("transaction", () -> tx.execute(status -> {
            lock(collection, id);
            Optional<Map<String, Object>> current = jdbc.query(
                    "select data::text from kv_document where collection = ? and id = ?",
                    rs -> rs.next() ? Optional.of(decodeObject(rs.getString(1))) : Optional.<Map<String, Object>>empty(),
                    c(collection), id);
            TxResult<T> r = fn.apply(current);
            if (r.delete()) {
                jdbc.update("delete from kv_document where collection = ? and id = ?", c(collection), id);
            } else if (r.newData() != null) {
                jdbc.update("""
                        insert into kv_document (collection, id, data) values (?, ?, ?::jsonb)
                        on conflict (collection, id) do update set data = excluded.data, updated_at = now()""",
                        c(collection), id, encodeObject(r.newData()));
            }
            return r.result();
        }));
    }

    /** Serialises transactions on one document, whether or not it exists yet. Released at commit or rollback. */
    private void lock(String collection, String id) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null, c(collection) + "/" + id);
    }

    private <T> T guard(String op, Supplier<T> call) {
        try {
            return call.get();
        } catch (DuplicateKeyException e) {
            throw new StoreUnavailableException(op + " failed (conflict)", e);
        } catch (DataAccessException e) {
            throw new StoreUnavailableException(op + " failed", e);
        }
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    // ── Value encoding ──────────────────────────────────────────────────────────

    String encodeObject(Map<String, Object> data) {
        return mapper.writeValueAsString(encode(data));
    }

    @SuppressWarnings("unchecked")
    JsonNode encode(Object v) {
        if (v == null) return mapper.nullNode();
        if (v instanceof String s) return mapper.getNodeFactory().stringNode(s);
        if (v instanceof Boolean b) return mapper.getNodeFactory().booleanNode(b);
        if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            return mapper.getNodeFactory().numberNode(((Number) v).longValue());
        }
        if (v instanceof Double || v instanceof Float) return mapper.getNodeFactory().numberNode(((Number) v).doubleValue());
        if (v instanceof Instant i) {
            ObjectNode ts = mapper.createObjectNode();
            ts.put(TS, i.toString());
            return ts;
        }
        if (v instanceof Map<?, ?> m) {
            ObjectNode o = mapper.createObjectNode();
            ((Map<String, Object>) m).forEach((k, val) -> o.set(k, encode(val)));
            return o;
        }
        if (v instanceof List<?> l) {
            ArrayNode a = mapper.createArrayNode();
            l.forEach(x -> a.add(encode(x)));
            return a;
        }
        throw new IllegalArgumentException("unsupported document value type " + v.getClass().getSimpleName());
    }

    Map<String, Object> decodeObject(String json) {
        JsonNode node = mapper.readTree(json);
        Object decoded = decode(node);
        if (decoded instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) decoded;
            return m;
        }
        return new HashMap<>();
    }

    Object decode(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isObject()) {
            if (n.size() == 1 && n.has(TS) && n.get(TS).isString()) return Instant.parse(n.get(TS).asString());
            Map<String, Object> m = new HashMap<>();
            for (var e : n.properties()) m.put(e.getKey(), decode(e.getValue()));
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode x : n) l.add(decode(x));
            return l;
        }
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        return n.asString();
    }
}
