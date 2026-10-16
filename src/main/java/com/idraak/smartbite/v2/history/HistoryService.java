package com.idraak.smartbite.v2.history;

import com.idraak.smartbite.v2.config.V2Properties;
import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Cloud meal history (D-024, BE-021): upload and download of a user's logged meals and favourites, a 24-month
 * retention window, and the daily cleanup. With no database configured every call answers 503
 * {@code FEATURE_UNAVAILABLE}.
 */
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    private static final Set<String> ENTRY_TYPES = Set.of("cooked", "ate_out", "logged");
    private static final Set<String> REACTIONS = Set.of("loved", "okay", "not_for_me");
    /** Clock skew allowed on client timestamps; a later "updated_at" is clamped to now. */
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final int CLEANUP_BATCH = 500;

    private final HistoryRepository repo;
    private final V2Properties.History cfg;
    private final Clock clock;

    /** @param repo null when no database is configured */
    public HistoryService(HistoryRepository repo, V2Properties.History cfg, Clock clock) {
        this.repo = repo;
        this.cfg = cfg;
        this.clock = clock;
    }

    public boolean available() {
        return repo != null;
    }

    public HistoryApi.PushResponse push(String uid, HistoryApi.PushRequest request) {
        HistoryRepository r = require();
        Instant now = clock.instant();
        Instant cutoff = retentionCutoff(now);
        List<HistoryApi.Entry> valid = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        int skipped = 0;
        for (HistoryApi.Entry e : request.entries()) {
            HistoryApi.Entry clean = tidy(e, now);
            if (clean == null) {
                rejected.add(e == null || e.id() == null ? "" : clip(e.id(), 64));
            } else if (clean.loggedAt().isBefore(cutoff)) {
                skipped++;
            } else {
                valid.add(clean);
            }
        }
        List<HistoryApi.FavoriteChange> favorites = new ArrayList<>();
        List<String> rejectedFavorites = new ArrayList<>();
        for (HistoryApi.FavoriteChange f : request.favorites() == null ? List.<HistoryApi.FavoriteChange>of() : request.favorites()) {
            HistoryApi.FavoriteChange clean = tidy(f, now);
            if (clean == null) rejectedFavorites.add(f == null || f.name() == null ? "" : clip(f.name(), 120));
            else favorites.add(clean);
        }
        int[] applied = db(() -> r.upsertEntries(uid, valid));
        int[] favApplied = db(() -> r.upsertFavorites(uid, favorites));
        db(() -> {
            r.touch(uid, now);
            return null;
        });
        int appliedCount = count(applied);
        skipped += valid.size() - appliedCount;
        return new HistoryApi.PushResponse(appliedCount, skipped, rejected, count(favApplied), rejectedFavorites);
    }

    public HistoryApi.Page page(String uid, String cursor, Integer limit) {
        HistoryRepository r = require();
        int size = Math.clamp(limit == null ? cfg.pageSize() : limit, 1, cfg.pageSize());
        Position after = decode(cursor);
        List<HistoryRepository.Row> rows = db(() -> r.changesAfter(uid, after == null ? null : after.time(),
                after == null ? null : after.id(), size + 1));
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        String next = rows.isEmpty() ? cursor : encode(rows.getLast());
        List<String> favorites = db(() -> r.favorites(uid));
        db(() -> {
            r.touch(uid, clock.instant());
            return null;
        });
        return new HistoryApi.Page(rows.stream().map(HistoryRepository.Row::entry).toList(), favorites, next, more,
                cfg.retentionMonths());
    }

    /** Scheduled: drops entries past the retention window and the history of inactive accounts. */
    public void cleanup() {
        if (repo == null) return;
        Instant now = clock.instant();
        try {
            int old = repo.deleteLoggedBefore(retentionCutoff(now), CLEANUP_BATCH);
            int inactive = repo.deleteInactiveSince(now.atZone(ZoneOffset.UTC).minusMonths(cfg.inactiveMonths()).toInstant(),
                    CLEANUP_BATCH);
            log.info("history cleanup: expired_entries={} inactive_accounts={}", old, inactive);
        } catch (DataAccessException e) {
            log.warn("history cleanup failed ({}); next run retries", e.getClass().getSimpleName());
        }
    }

    /** Account deletion. @return rows deleted, or -1 when no database is configured */
    public int deleteUser(String uid) {
        if (repo == null) return -1;
        return db(() -> repo.deleteAll(uid));
    }

    Instant retentionCutoff(Instant now) {
        return now.atZone(ZoneOffset.UTC).minusMonths(cfg.retentionMonths()).toInstant();
    }

    // ── Tidying ────────────────────────────────────────────────────────────────

    /** A clean copy of the entry, or null if its id, name or timestamps are unusable. */
    HistoryApi.Entry tidy(HistoryApi.Entry e, Instant now) {
        if (e == null || e.id() == null || !ID.matcher(e.id()).matches()) return null;
        String name = text(e.name(), 120);
        if (name == null || e.loggedAt() == null || e.updatedAt() == null) return null;
        if (e.loggedAt().isAfter(now.plus(1, ChronoUnit.DAYS))) return null;
        Instant updated = e.updatedAt().isAfter(now.plus(SKEW)) ? now : e.updatedAt();
        String type = e.entryType() != null && ENTRY_TYPES.contains(e.entryType()) ? e.entryType() : "logged";
        String reaction = e.reaction() != null && REACTIONS.contains(e.reaction()) ? e.reaction() : null;
        Integer calories = e.calories() != null && e.calories() >= 0 && e.calories() <= 20_000 ? e.calories() : null;
        Integer servings = e.servings() != null && e.servings() >= 1 && e.servings() <= 50 ? e.servings() : null;
        String image = e.imageUrl() != null && e.imageUrl().matches("^https?://\\S{1,490}$") ? e.imageUrl() : null;
        List<String> tags = new ArrayList<>();
        if (e.tags() != null) {
            for (String t : e.tags()) {
                String tag = text(t, 40);
                if (tag != null && tags.size() < 10) tags.add(tag);
            }
        }
        return new HistoryApi.Entry(e.id(), name, type, text(e.mealType(), 40), reaction, calories, servings,
                text(e.area(), 120), note(e.note()), image, List.copyOf(tags), e.loggedAt().truncatedTo(ChronoUnit.MICROS),
                updated.truncatedTo(ChronoUnit.MICROS), e.deleted());
    }

    HistoryApi.FavoriteChange tidy(HistoryApi.FavoriteChange f, Instant now) {
        if (f == null || f.updatedAt() == null) return null;
        String name = text(f.name(), 120);
        if (name == null) return null;
        Instant updated = f.updatedAt().isAfter(now.plus(SKEW)) ? now : f.updatedAt();
        return new HistoryApi.FavoriteChange(name, f.favorite(), updated.truncatedTo(ChronoUnit.MICROS));
    }

    private static String text(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}", " ").strip();
        return t.isEmpty() ? null : clip(t, max);
    }

    /** Notes keep their line breaks; other control characters go. */
    private static String note(String s) {
        if (s == null) return null;
        String t = CONTROL.matcher(s).replaceAll(" ").strip();
        return t.isEmpty() ? null : clip(t, 500);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).strip();
    }

    // ── Cursor: base64url("v1:<epoch micros>:<id>") ──────────────────────────────

    record Position(Instant time, String id) {}

    static String encode(HistoryRepository.Row row) {
        Instant t = row.serverUpdatedAt();
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, t);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1:" + micros + ":" + row.entry().id()).getBytes(StandardCharsets.UTF_8));
    }

    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            if (parts.length != 3 || !"v1".equals(parts[0]) || !ID.matcher(parts[2]).matches()) throw new IllegalArgumentException();
            return new Position(Instant.EPOCH.plus(Long.parseLong(parts[1]), ChronoUnit.MICROS), parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "cursor is not valid; start again without one");
        }
    }

    private HistoryRepository require() {
        if (repo == null) throw new ApiException(ErrorCode.FEATURE_UNAVAILABLE);
        return repo;
    }

    private static <T> T db(Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            throw ApiException.providerUnavailable(10, e);
        }
    }

    private static int count(int[] results) {
        int n = 0;
        for (int r : results) if (r > 0) n++;
        return n;
    }
}
