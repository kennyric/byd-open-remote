package com.bydwatch.companion.data;

import android.net.Uri;

import com.bydwatch.companion.model.VehicleStatus;

/**
 * The read-only contract by which other apps (the patched Google Maps) read the vehicle's charge
 * and electric range. Kept free of Android framework behaviour so it can be unit-tested.
 */
public final class VehicleStatusContract {
    /**
     * Authority the provider is published under. Derived from the running package so a debug/demo
     * build (com.bydopenremote.demo) carries its own provider instead of colliding with the release
     * app's provider. Falls back to the release values off-device (JVM tests).
     */
    public static final String AUTHORITY = appId() + ".vehicle";
    public static final String PATH_STATUS = "status";
    private static volatile Uri contentUri;

    /** Release application id, also the default when no package can be resolved. */
    private static final String RELEASE_PACKAGE = "com.bydopenremote.app";

    /** Application id of the running process, so permission/action names cannot collide. */
    private static String appId() {
        return packagePrefix();
    }

    private static String packagePrefix() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (app instanceof android.content.Context) {
                String name = ((android.content.Context) app).getPackageName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
            // Not on Android, or no current application: tests and tooling use the release id.
        }
        return RELEASE_PACKAGE;
    }

    /** The one row this provider serves; built lazily so JVM tests never touch Android's stubs. */
    public static Uri contentUri() {
        Uri uri = contentUri;
        if (uri == null) {
            uri = Uri.parse("content://" + AUTHORITY + "/" + PATH_STATUS);
            contentUri = uri;
        }
        return uri;
    }
    /** Normal-level permission: a re-signed Maps cannot hold a signature one. */
    public static final String PERMISSION = appId() + ".permission.READ_VEHICLE";
    /** Sent after every cache update so listeners refetch immediately instead of polling. */
    public static final String ACTION_STATUS_CHANGED = appId() + ".action.VEHICLE_STATUS_CHANGED";
    /** Sent by a consumer that wants fresher data than the periodic sync provides. */
    public static final String ACTION_REQUEST_REFRESH = appId() + ".action.REQUEST_REFRESH";
    /** Shortest gap between consumer-requested cloud fetches, so nobody can hammer the account. */
    public static final long MIN_REFRESH_GAP_MS = 2L * 60L * 1000L;
    /**
     * The Maps patch owns the cadence: its "fetch every N minutes" setting sends a refresh
     * request at that interval. The app only coalesces accidental bursts; it does not impose
     * its own schedule on top.
     */
    public static final long CONSUMER_REFRESH_MIN_GAP_MS = 20L * 1000L;
    /** Extra on {@code call("refresh")}: distance the consumer has driven since the cached reading. */
    public static final String EXTRA_DISTANCE_KM = "distance_km";
    /** A larger reported gap is a bad value, not a drive. */
    static final double MAX_ESTIMATE_DISTANCE_KM = 500d;
    public static final String MIME_TYPE = "vnd.android.cursor.item/vnd.bydopenremote.vehicle_status";

    public static final String COLUMN_SOC = "soc";
    public static final String COLUMN_RANGE_KM = "range_km";
    public static final String COLUMN_TS = "ts";
    public static final String COLUMN_DISTANCE_UNIT = "distance_unit";

    /** Only what a consumer renders: charge, range, freshness and unit. */
    public static final String[] COLUMNS = {
            COLUMN_SOC, COLUMN_RANGE_KM, COLUMN_TS, COLUMN_DISTANCE_UNIT
    };

    /** Unit reported when nothing better is known, matching Maps' own default. */
    static final String DEFAULT_UNIT = "km";
    /** One log tag for the whole integration, so `adb logcat -s BydTelemetry` shows the story. */
    public static final String LOG_TAG = "BydTelemetry";

    /** Logs through one seam, silently: the same classes run in JVM tests where Log is a stub. */
    public static void log(String message) {
        try {
            android.util.Log.i(LOG_TAG, message);
        } catch (RuntimeException stub) {
            // Not on Android (unit test): the message is irrelevant there.
        }
    }

    private VehicleStatusContract() {
    }

    /**
     * The column values for one row, in {@link #COLUMNS} order. A missing status yields the
     * "unknown" values rather than nulls, so callers can render nothing instead of failing.
     */
    public static Object[] row(VehicleStatus status) {
        return row(status, 0f);
    }

    /**
     * Same as {@link #row(VehicleStatus)}, but when {@code batteryCapacityKwh > 0} and the car
     * reports recent kWh/100km, computes range from {@code batteryCapacityKwh} and recent
     * consumption; falls back to {@code status.rangeKm} when either is unavailable.
     */
    public static Object[] row(VehicleStatus status, float batteryCapacityKwh) {
        return row(status, batteryCapacityKwh, 0d);
    }

    /**
     * The row with a display-only estimate for a reading that is older than the distance driven:
     * {@code distanceSinceReadingKm} is what the consumer has travelled since this reading was
     * taken. SoC and range are reduced by that distance at the car's own recent consumption, so a
     * sparse sync degrades gradually instead of freezing. Never written back into the cache.
     */
    public static Object[] row(VehicleStatus status, float batteryCapacityKwh,
                               double distanceSinceReadingKm) {
        if (status == null) {
            return new Object[]{-1, -1, 0L, DEFAULT_UNIT};
        }
        String unit = status.distanceUnit == null || status.distanceUnit.isEmpty()
                ? DEFAULT_UNIT : status.distanceUnit;
        int soc = status.soc;
        int range = effectiveRangeKm(status, batteryCapacityKwh);
        if (canEstimate(status, distanceSinceReadingKm)) {
            double capacity = batteryCapacityKwh > 0f
                    ? batteryCapacityKwh : derivedCapacityKwh(status);
            if (capacity > 0d) {
                double usedKwh = distanceSinceReadingKm * status.recentKwhPer100Km / 100d;
                int dropPoints = (int) Math.floor(usedKwh / capacity * 100d);
                soc = Math.max(0, status.soc - dropPoints);
                range = Math.max(0, range - (int) Math.round(distanceSinceReadingKm));
            }
        }
        return new Object[]{soc, range, toEpochMillis(status.ts), unit};
    }

    /** Only a positive, plausible distance on a discharging car may move the estimate. */
    static boolean canEstimate(VehicleStatus status, double distanceKm) {
        return status != null
                && distanceKm > 0d
                && distanceKm <= MAX_ESTIMATE_DISTANCE_KM
                && status.soc > 0
                && status.soc <= 100
                && status.recentKwhPer100Km > 0d
                && status.chargingState != VehicleStatus.CHARGING_ACTIVE
                && status.chargingState != VehicleStatus.CHARGING_CONNECTED;
    }

    /** Pack size inferred from the reading itself when the user has not set the kWh override. */
    static double derivedCapacityKwh(VehicleStatus status) {
        if (status == null || status.soc <= 0 || status.rangeKm <= 0
                || status.recentKwhPer100Km <= 0d) {
            return 0d;
        }
        return status.rangeKm * status.recentKwhPer100Km / status.soc;
    }

    static int effectiveRangeKm(VehicleStatus status, float batteryCapacityKwh) {
        if (status == null) return -1;
        if (batteryCapacityKwh > 0f && status.soc > 0 && status.recentKwhPer100Km > 0d) {
            return Math.max(0, (int) Math.round((batteryCapacityKwh * status.soc) / status.recentKwhPer100Km));
        }
        return status.rangeKm;
    }

    /**
     * The vehicle payload carries seconds; consumers compare against wall-clock milliseconds.
     * Anything below this threshold is read as seconds and scaled, so a consumer's staleness rule
     * cannot silently discard every reading.
     */
    private static final long SECONDS_THRESHOLD = 100_000_000_000L;

    static long toEpochMillis(long timestamp) {
        if (timestamp <= 0) return 0L;
        return timestamp < SECONDS_THRESHOLD ? timestamp * 1000L : timestamp;
    }
}
