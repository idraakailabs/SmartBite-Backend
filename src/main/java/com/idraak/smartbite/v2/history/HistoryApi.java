package com.idraak.smartbite.v2.history;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes of {@code /api/v2/me/history} (D-024). JSON is snake_case like the rest of v2. Only the envelope is
 * validated strictly; individual entries are tidied (clipped, control characters removed) and only structurally
 * broken ones are rejected by id, so one odd entry never blocks a user's whole sync.
 */
public final class HistoryApi {

    private HistoryApi() {
    }

    /** A logged meal. {@code updated_at} is the client's last change; the latest one wins. */
    public record Entry(
            String id,
            String name,
            String entryType,
            String mealType,
            String reaction,
            Integer calories,
            Integer servings,
            String area,
            String note,
            String imageUrl,
            List<String> tags,
            Instant loggedAt,
            Instant updatedAt,
            boolean deleted
    ) {}

    /** A favourite dish name; {@code favorite = false} removes it. */
    public record FavoriteChange(String name, boolean favorite, Instant updatedAt) {}

    public record PushRequest(
            @NotNull @Size(max = 100) List<@Valid Entry> entries,
            @Size(max = 100) List<@Valid FavoriteChange> favorites
    ) {}

    /**
     * {@code entries_skipped}: older than the stored version, or beyond the retention window.
     * {@code rejected_ids}: structurally invalid (id, name or timestamps); the client should drop them.
     */
    public record PushResponse(int entriesApplied, int entriesSkipped, List<String> rejectedIds, int favoritesApplied,
                               List<String> rejectedFavorites) {}

    /**
     * One page of changes since {@code cursor}, oldest first, tombstones included. {@code favorites} is the full
     * current set. Pass {@code next_cursor} to the next call; when {@code has_more} is false, keep it for the next sync.
     */
    public record Page(List<Entry> entries, List<String> favorites, String nextCursor, boolean hasMore, int retentionMonths) {}
}
