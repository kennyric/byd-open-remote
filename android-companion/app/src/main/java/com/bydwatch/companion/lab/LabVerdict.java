package com.bydwatch.companion.lab;

import com.overdrive.app.byd.cloud.ChargeLimitValues;

/**
 * Cloud-level outcome of an AC current-limit write followed by a re-read.
 *
 * <p>The verdict never claims the car physically changed; it only says what
 * the cloud reported after the app wrote the value.
 */
public enum LabVerdict {
    APPLIED,
    UNCHANGED,
    CHANGED,
    PENDING,
    REJECTED,
    UNREACHABLE,
    ERROR;

    public static LabVerdict classify(String before,
                                      String requested,
                                      String after,
                                      String code,
                                      String error,
                                      boolean reReadCompleted) {
        if (error != null) return ERROR;
        if ("6002".equals(code)) return UNREACHABLE;
        if (code != null && !code.isEmpty() && !"0".equals(code)) return REJECTED;
        if (!reReadCompleted) return PENDING;
        if (ChargeLimitValues.same(after, requested)) return APPLIED;
        if (ChargeLimitValues.same(after, before)) return UNCHANGED;
        return CHANGED;
    }
}
