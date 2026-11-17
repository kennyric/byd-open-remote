package com.bydwatch.companion.data;

import android.content.Context;
import android.content.Intent;

/**
 * Tells a listening app that the cached vehicle status changed, so it refetches the provider
 * immediately instead of polling. The broadcast carries no data: receivers re-query, which keeps
 * a single source of truth.
 */
public final class VehicleStatusBroadcaster {
    /** Package that receives the nudge by default; the patched Google Maps. */
    public static final String DEFAULT_TARGET_PACKAGE = "org.ungoogled.android.apps.maps";

    private static volatile String targetPackage = DEFAULT_TARGET_PACKAGE;

    private VehicleStatusBroadcaster() {
    }

    /** Points the nudge at another package, for a differently named Maps build. */
    public static void setTargetPackage(String value) {
        targetPackage = value == null ? "" : value.trim();
    }

    public static String getTargetPackage() {
        return targetPackage;
    }

    /** Sends the nudge; a blank target or missing context is a no-op rather than an error. */
    public static void notifyChanged(Context context, String target) {
        if (context == null) return;
        String destination = target == null ? "" : target.trim();
        if (destination.isEmpty()) return;
        Intent intent = new Intent(VehicleStatusContract.ACTION_STATUS_CHANGED);
        intent.setPackage(destination);
        try {
            context.sendBroadcast(intent);
        } catch (RuntimeException ignored) {
            // A broadcast is an optimisation; the provider stays the source of truth.
        }
    }

    /** Sends the nudge to the configured target. */
    public static void notifyChanged(Context context) {
        notifyChanged(context, targetPackage);
    }
}
