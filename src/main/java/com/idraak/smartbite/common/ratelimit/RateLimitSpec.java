package com.idraak.smartbite.common.ratelimit;

import java.time.Duration;

/** "At most {@code permits} requests per {@code period}", refilled continuously (token bucket). */
public record RateLimitSpec(int permits, Duration period) {

    public RateLimitSpec {
        if (permits < 1) throw new IllegalArgumentException("permits must be >= 1");
        if (period == null || period.isZero() || period.isNegative()) throw new IllegalArgumentException("period must be > 0");
    }

    public static RateLimitSpec perMinute(int permits) {
        return new RateLimitSpec(permits, Duration.ofMinutes(1));
    }

    public static RateLimitSpec perHour(int permits) {
        return new RateLimitSpec(permits, Duration.ofHours(1));
    }

    public static RateLimitSpec perDay(int permits) {
        return new RateLimitSpec(permits, Duration.ofDays(1));
    }
}
