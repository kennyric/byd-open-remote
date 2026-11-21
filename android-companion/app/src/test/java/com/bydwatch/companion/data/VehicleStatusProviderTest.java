package com.bydwatch.companion.data;

import android.content.ContentValues;
import android.database.Cursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the provider itself rather than the contract: a consumer with no vehicle signed in must
 * still get exactly one, well-formed row so it can render nothing instead of crashing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VehicleStatusProviderTest {

    private VehicleStatusProvider provider() {
        VehicleStatusProvider provider = new VehicleStatusProvider();
        assertTrue(provider.onCreate());
        return provider;
    }

    @Test
    public void queryReturnsExactlyOneWellFormedRow() {
        Cursor cursor = provider().query(VehicleStatusContract.contentUri(), null, null, null, null);

        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        assertTrue(cursor.moveToFirst());
        assertEquals(4, cursor.getColumnCount());
        // Nothing signed in yet: unknown values, never a null row or a throw.
        assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_SOC)));
        assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_RANGE_KM)));
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_TS)));
        assertEquals("km", cursor.getString(
                cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_DISTANCE_UNIT)));
        cursor.close();
    }

    @Test
    public void typeIsThePublishedItemMime() {
        assertEquals(VehicleStatusContract.MIME_TYPE,
                provider().getType(VehicleStatusContract.contentUri()));
    }

    @Test
    public void providerIsReadOnly() {
        VehicleStatusProvider provider = provider();
        assertNull(provider.insert(VehicleStatusContract.contentUri(), new ContentValues()));
        assertEquals(0, provider.update(VehicleStatusContract.contentUri(),
                new ContentValues(), null, null));
        assertEquals(0, provider.delete(VehicleStatusContract.contentUri(), null, null));
    }
}
