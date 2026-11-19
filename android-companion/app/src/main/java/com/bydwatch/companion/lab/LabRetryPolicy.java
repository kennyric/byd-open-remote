package com.bydwatch.companion.lab;

/** Bounded re-read schedule for confirming an AC current-limit write. */
public final class LabRetryPolicy {

    public final int attempts;
    public final long delayMs;

    public LabRetryPolicy(int attempts, long delayMs) {
        this.attempts = Math.max(1, attempts);
        this.delayMs = Math.max(0L, delayMs);
    }

    public static LabRetryPolicy defaults() {
        return new LabRetryPolicy(6, 2_000L);
    }
}
