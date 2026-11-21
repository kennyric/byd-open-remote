package com.bydwatch.companion.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.model.VehicleStatus;

/**
 * Serves the repository's cached vehicle status as a single read-only row.
 *
 * <p>Two gates, because the data leaves the app: the manifest permission, and a package check that
 * only answers the configured consumer (the patched Maps). Anything else gets an empty result, so a
 * caller cannot probe the vehicle by accident.
 */
public final class VehicleStatusProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!allowedCaller()) {
            // Empty rather than throwing: a refused probe must not become a crash in either app.
            VehicleStatusContract.log("query refused for uid " + Binder.getCallingUid());
            return new MatrixCursor(VehicleStatusContract.COLUMNS);
        }
        Object[] row = VehicleStatusContract.row(cachedStatus(), batteryCapacityKwh());
        VehicleStatusContract.log("served row to uid " + Binder.getCallingUid());
        MatrixCursor cursor = new MatrixCursor(VehicleStatusContract.COLUMNS);
        cursor.addRow(row);
        return cursor;
    }

    /**
     * Explicit path for a consumer that wants to force a fetch: ContentResolver.call(uri,
     * "refresh", null, null). Returns the status timestamp after scheduling the request.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if (!allowedCaller()) {
            result.putBoolean("requested", false);
            return result;
        }
        if ("refresh".equals(method)) {
            BydWatchApp app = BydWatchApp.getInstance();
            BydRepository repository = app != null ? app.getRepository() : null;
            if (repository != null) {
                repository.refreshNow(VehicleStatusContract.CONSUMER_REFRESH_MIN_GAP_MS);
                result.putBoolean("requested", true);
            }
        }
        VehicleStatus status = cachedStatus();
        if (status != null) result.putLong("ts", VehicleStatusContract.toEpochMillis(status.ts));
        return result;
    }

    /** True for this app itself, or for the one configured consumer package. */
    private static boolean allowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        try {
            BydWatchApp app = BydWatchApp.getInstance();
            if (app == null) return false;
            String allowed = app.getConfigStore().getVehicleStatusTargetPackage();
            if (allowed == null || allowed.trim().isEmpty()) return false;
            String[] packages = app.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            for (String name : packages) {
                if (VehicleStatusBroadcaster.isAllowedPackage(allowed, name)) return true;
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    private static float batteryCapacityKwh() {
        try {
            BydWatchApp app = BydWatchApp.getInstance();
            return app == null ? 0f : app.getConfigStore().getBatteryCapacityKwh();
        } catch (Throwable unavailable) {
            return 0f;
        }
    }

    private static com.bydwatch.companion.model.VehicleStatus cachedStatus() {
        // A provider must never throw at its consumer: an unavailable repository (not signed in,
        // keystore unavailable, process starting) yields the "unknown" row instead.
        try {
            BydWatchApp app = BydWatchApp.getInstance();
            if (app == null) return null;
            BydRepository repository = app.getRepository();
            return repository == null ? null : repository.getCachedStatus();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return VehicleStatusContract.MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        VehicleStatusContract.log("insert refused");
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        VehicleStatusContract.log("update refused");
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        VehicleStatusContract.log("delete refused");
        return 0;
    }
}
