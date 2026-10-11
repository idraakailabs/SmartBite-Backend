package com.idraak.smartbite.common.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {

    public static final RateLimitDecision ALLOWED = new RateLimitDecision(true, 0);

    public static RateLimitDecision rejected(long retryAfterSeconds) {
        return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
    }
}
