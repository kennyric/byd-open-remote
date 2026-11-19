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
    /** MicroG-RE variant of the patched Google Maps. */
    public static final String MICROG_TARGET_PACKAGE = "org.ungoogled.android.apps.maps.microg";

    /** True when {@code pkg} matches either the configured target or the MicroG-RE Maps package. */
    public static boolean isAllowedPackage(String configured, String pkg) {
        if (pkg == null) return false;
        String clean = pkg.trim();
        if (clean.isEmpty()) return false;
        if (configured != null && !configured.trim().isEmpty() && configured.trim().equals(clean)) {
            return true;
        }
        return (configured == null || configured.trim().isEmpty()
                || DEFAULT_TARGET_PACKAGE.equals(configured.trim()))
                && MICROG_TARGET_PACKAGE.equals(clean);
    }

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

    /**
     * Grants the target read access for this process lifetime, so the integration works no matter
     * which app was installed first: a normal-level permission is only granted at the consumer's
     * install time, which fails when the consumer predates this app.
     */
    public static void grantAccess(Context context, String target) {
        if (context == null) return;
        String destination = target == null ? "" : target.trim();
        if (destination.isEmpty()) return;
        try {
            context.getPackageManager().getPackageInfo(destination, 0);
        } catch (Exception absent) {
            return; // Consumer not installed yet; the next status change retries.
        }
        try {
            context.grantUriPermission(destination, VehicleStatusContract.contentUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            VehicleStatusContract.log("granted read to " + destination);
        } catch (RuntimeException refused) {
            VehicleStatusContract.log("grant refused for " + destination);
        }
    }

    /** Sends the nudge; a blank target or missing context is a no-op rather than an error. */
    public static void notifyChanged(Context context, String target) {
        if (context == null) return;
        String destination = target == null ? "" : target.trim();
        if (destination.isEmpty()) return;
        Intent intent = new Intent(VehicleStatusContract.ACTION_STATUS_CHANGED);
        intent.setPackage(destination);
        try {
            // Runtime grant first: it survives any install order, because it does not depend on a
            // normal permission having existed when the consumer was installed.
            grantAccess(context, destination);
            context.sendBroadcast(intent);
            VehicleStatusContract.log("nudge -> " + destination);
        } catch (RuntimeException ignored) {
            // A broadcast is an optimisation; the provider stays the source of truth.
        }
    }

    /** Sends the nudge to the configured target (and MicroG-RE variant if installed). */
    public static void notifyChanged(Context context) {
        String destination = targetPackage;
        try {
            com.bydwatch.companion.BydWatchApp app = com.bydwatch.companion.BydWatchApp.getInstance();
            if (app != null) {
                String configured = app.getConfigStore().getVehicleStatusTargetPackage();
                if (configured != null && !configured.trim().isEmpty()) destination = configured.trim();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the compiled-in default.
        }
        notifyChanged(context, destination);
        if (DEFAULT_TARGET_PACKAGE.equals(destination)) {
            notifyChanged(context, MICROG_TARGET_PACKAGE);
        }
    }
}
