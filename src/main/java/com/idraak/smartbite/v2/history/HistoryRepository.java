package com.idraak.smartbite.v2.history;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * SQL for {@code meal_history}, {@code favorite_dish} and {@code user_activity} (migration V1). Every statement is
 * scoped by uid; the uid always comes from the verified token, never from a request body.
 */
public class HistoryRepository {

    /** A stored row plus its sync position. */
    public record Row(HistoryApi.Entry entry, Instant serverUpdatedAt) {}

    private static final String SETTLED = "clock_timestamp() - interval '2 seconds'";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HistoryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Upserts entries; the latest {@code client_updated_at} wins, so a stale write from another device is a no-op.
     * Each applied row moves to the end of the sync order. @return per entry, 1 if applied, 0 if skipped
     */
    public int[] upsertEntries(String uid, List<HistoryApi.Entry> entries) {
        if (entries.isEmpty()) return new int[0];
        return jdbc.batchUpdate("""
                insert into meal_history (uid, id, name, entry_type, meal_type, reaction, calories, servings, area, note,
                                          image_url, tags, logged_at, client_updated_at, deleted, server_updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, clock_timestamp())
                on conflict (uid, id) do update set
                    name = excluded.name, entry_type = excluded.entry_type, meal_type = excluded.meal_type,
                    reaction = excluded.reaction, calories = excluded.calories, servings = excluded.servings,
                    area = excluded.area, note = excluded.note, image_url = excluded.image_url, tags = excluded.tags,
                    logged_at = excluded.logged_at, client_updated_at = excluded.client_updated_at,
                    deleted = excluded.deleted, server_updated_at = clock_timestamp()
                where meal_history.client_updated_at <= excluded.client_updated_at""",
                entries, entries.size(), (ps, e) -> {
                    ps.setString(1, uid);
                    ps.setString(2, e.id());
                    ps.setString(3, e.name());
                    ps.setString(4, e.entryType());
                    ps.setString(5, e.mealType());
                    ps.setString(6, e.reaction());
                    ps.setObject(7, e.calories());
                    ps.setObject(8, e.servings());
                    ps.setString(9, e.area());
                    ps.setString(10, e.note());
                    ps.setString(11, e.imageUrl());
                    ps.setString(12, mapper.writeValueAsString(e.tags() == null ? List.of() : e.tags()));
                    ps.setObject(13, utc(e.loggedAt()));
                    ps.setObject(14, utc(e.updatedAt()));
                    ps.setBoolean(15, e.deleted());
                })[0];
    }

    /** Latest change wins per dish name. @return per change, 1 if applied, 0 if skipped */
    public int[] upsertFavorites(String uid, List<HistoryApi.FavoriteChange> changes) {
        if (changes.isEmpty()) return new int[0];
        return jdbc.batchUpdate("""
                insert into favorite_dish (uid, name, favorite, client_updated_at, server_updated_at)
                values (?, ?, ?, ?, clock_timestamp())
                on conflict (uid, name) do update set
                    favorite = excluded.favorite, client_updated_at = excluded.client_updated_at,
                    server_updated_at = clock_timestamp()
                where favorite_dish.client_updated_at <= excluded.client_updated_at""",
                changes, changes.size(), (ps, f) -> {
                    ps.setString(1, uid);
                    ps.setString(2, f.name());
                    ps.setBoolean(3, f.favorite());
                    ps.setObject(4, utc(f.updatedAt()));
                })[0];
    }

    /**
     * Changes after the (serverUpdatedAt, id) position, oldest first; null position = from the start. Rows changed in
     * the last two seconds are left for the next sync, so a write still committing can't be skipped by a cursor
     * that has already moved past it.
     */
    public List<Row> changesAfter(String uid, Instant afterTime, String afterId, int limit) {
        RowMapper<Row> rows = (rs, i) -> new Row(new HistoryApi.Entry(
                rs.getString("id"), rs.getString("name"), rs.getString("entry_type"), rs.getString("meal_type"),
                rs.getString("reaction"), (Integer) rs.getObject("calories"), (Integer) rs.getObject("servings"),
                rs.getString("area"), rs.getString("note"), rs.getString("image_url"), tags(rs.getString("tags")),
                instant(rs.getObject("logged_at", OffsetDateTime.class)),
                instant(rs.getObject("client_updated_at", OffsetDateTime.class)), rs.getBoolean("deleted")),
                instant(rs.getObject("server_updated_at", OffsetDateTime.class)));
        String columns = "id, name, entry_type, meal_type, reaction, calories, servings, area, note, image_url, tags::text as tags, "
                + "logged_at, client_updated_at, deleted, server_updated_at";
        if (afterTime == null) {
            return jdbc.query("select " + columns + " from meal_history where uid = ? and server_updated_at < " + SETTLED
                    + " order by server_updated_at, id limit ?",
                    rows, uid, limit);
        }
        return jdbc.query("select " + columns + " from meal_history where uid = ? and (server_updated_at, id) > (?, ?) "
                + "and server_updated_at < " + SETTLED + " order by server_updated_at, id limit ?", rows, uid, utc(afterTime), afterId, limit);
    }

    public List<String> favorites(String uid) {
        return jdbc.queryForList("select name from favorite_dish where uid = ? and favorite order by name", String.class, uid);
    }

    /** Records that this user synced; written at most once an hour per user. */
    public void touch(String uid, Instant now) {
        jdbc.update("""
                insert into user_activity (uid, last_seen_at) values (?, ?)
                on conflict (uid) do update set last_seen_at = excluded.last_seen_at
                where user_activity.last_seen_at < excluded.last_seen_at - interval '1 hour'""", uid, utc(now));
    }

    /** Retention: deletes entries logged before {@code cutoff}, in batches. @return deleted rows */
    public int deleteLoggedBefore(Instant cutoff, int batch) {
        int total = 0;
        int n;
        do {
            n = jdbc.update("""
                    delete from meal_history where ctid in (
                        select ctid from meal_history where logged_at < ? limit ?)""", utc(cutoff), batch);
            total += n;
        } while (n == batch);
        return total;
    }

    /** Accounts that haven't synced since {@code cutoff} lose their cloud history and favourites. @return accounts */
    public int deleteInactiveSince(Instant cutoff, int batch) {
        int total = 0;
        List<String> uids;
        do {
            uids = jdbc.queryForList("select uid from user_activity where last_seen_at < ? limit ?", String.class, utc(cutoff), batch);
            for (String uid : uids) deleteAll(uid);
            total += uids.size();
        } while (uids.size() == batch);
        return total;
    }

    /** Account deletion and inactivity: removes everything history-related for one user. @return deleted rows */
    public int deleteAll(String uid) {
        int n = jdbc.update("delete from meal_history where uid = ?", uid);
        n += jdbc.update("delete from favorite_dish where uid = ?", uid);
        n += jdbc.update("delete from user_activity where uid = ?", uid);
        return n;
    }

    private List<String> tags(String json) {
        if (json == null) return List.of();
        List<String> out = new ArrayList<>();
        for (var n : mapper.readTree(json)) if (n.isString()) out.add(n.asString());
        return out;
    }

    static OffsetDateTime utc(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
