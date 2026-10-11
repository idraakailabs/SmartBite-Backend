package com.idraak.smartbite.v2.ai;

import java.time.Duration;

/** A request-wide time budget measured on the monotonic clock. */
public final class Deadline {

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline in(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    public Duration remaining() {
        long left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    public boolean hasAtLeast(Duration d) {
        return remaining().compareTo(d) >= 0;
    }
}
