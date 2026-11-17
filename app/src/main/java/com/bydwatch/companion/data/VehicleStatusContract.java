package com.bydwatch.companion.data;

import android.net.Uri;

import com.bydwatch.companion.model.VehicleStatus;

/**
 * The read-only contract by which other apps (the patched Google Maps) read the vehicle's charge
 * and electric range. Kept free of Android framework behaviour so it can be unit-tested.
 */
public final class VehicleStatusContract {
    /** Authority the provider is published under. */
    public static final String AUTHORITY = "com.bydopenremote.app.vehicle";
    public static final String PATH_STATUS = "status";
    private static volatile Uri contentUri;

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
    public static final String PERMISSION = "com.bydopenremote.app.permission.READ_VEHICLE";
    /** Sent after every cache update so listeners refetch immediately instead of polling. */
    public static final String ACTION_STATUS_CHANGED = "com.bydopenremote.app.action.VEHICLE_STATUS_CHANGED";
    /** Sent by a consumer that wants fresher data than the periodic sync provides. */
    public static final String ACTION_REQUEST_REFRESH = "com.bydopenremote.app.action.REQUEST_REFRESH";
    /** Shortest gap between consumer-requested cloud fetches, so nobody can hammer the account. */
    public static final long MIN_REFRESH_GAP_MS = 5L * 60L * 1000L;
    public static final String MIME_TYPE = "vnd.android.cursor.item/vnd.bydopenremote.vehicle_status";

    public static final String COLUMN_SOC = "soc";
    public static final String COLUMN_RANGE_KM = "range_km";
    public static final String COLUMN_TS = "ts";
    public static final String COLUMN_DISTANCE_UNIT = "distance_unit";
    public static final String COLUMN_VIN = "vin";

    public static final String[] COLUMNS = {
            COLUMN_SOC, COLUMN_RANGE_KM, COLUMN_TS, COLUMN_DISTANCE_UNIT, COLUMN_VIN
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
        if (status == null) {
            return new Object[]{-1, -1, 0L, DEFAULT_UNIT, null};
        }
        String unit = status.distanceUnit == null || status.distanceUnit.isEmpty()
                ? DEFAULT_UNIT : status.distanceUnit;
        return new Object[]{status.soc, status.rangeKm, toEpochMillis(status.ts), unit, status.vin};
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
