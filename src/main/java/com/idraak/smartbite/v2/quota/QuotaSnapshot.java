package com.idraak.smartbite.v2.quota;

import java.time.Instant;
import java.time.LocalDate;

/** The {@code quota} object of v2 responses. Limit, used, remaining and reset_at are null when unlimited. */
public record QuotaSnapshot(boolean isPremium, boolean unlimited, Integer limit, Integer used, Integer remaining,
                            LocalDate day, String timezone, Instant resetAt) {

    public static QuotaSnapshot premium(LocalDate day, String timezone) {
        return new QuotaSnapshot(true, true, null, null, null, day, timezone, null);
    }

    public static QuotaSnapshot free(int limit, int used, LocalDate day, String timezone, Instant resetAt) {
        return new QuotaSnapshot(false, false, limit, used, Math.max(0, limit - used), day, timezone, resetAt);
    }
}
