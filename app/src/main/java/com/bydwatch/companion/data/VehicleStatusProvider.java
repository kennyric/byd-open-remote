package com.bydwatch.companion.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.bydwatch.companion.BydWatchApp;

/**
 * Serves the repository's cached vehicle status as a single read-only row, so an external app can
 * read the charge and range without being able to write anything back.
 */
public final class VehicleStatusProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        com.bydwatch.companion.model.VehicleStatus status = cachedStatus();
        Object[] row = VehicleStatusContract.row(status);
        VehicleStatusContract.log("served soc=" + row[0] + "% range=" + row[1] + row[3] + " ts=" + row[2]);
        MatrixCursor cursor = new MatrixCursor(VehicleStatusContract.COLUMNS);
        cursor.addRow(row);
        return cursor;
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
        throw new UnsupportedOperationException("Vehicle status is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Vehicle status is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Vehicle status is read-only");
    }
}
