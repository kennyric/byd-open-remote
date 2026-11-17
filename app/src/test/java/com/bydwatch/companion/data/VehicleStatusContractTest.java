package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VehicleStatusContractTest {

    @Test
    public void mapsAStatusOntoTheFiveColumns() {
        long stamp = 1_700_000_000_000L;
        VehicleStatus status = new VehicleStatus(62, 310, true, false, null, 22, stamp);

        Object[] row = VehicleStatusContract.row(status);

        assertEquals(62, row[0]);
        assertEquals(310, row[1]);
        assertEquals(stamp, ((Number) row[2]).longValue());
        assertNotNull(row[3]);
        assertTrue(row[4] == null || "".equals(row[4]));
        assertEquals(VehicleStatusContract.COLUMNS.length, row.length);
    }

    @Test
    public void missingStatusYieldsUnknownValues() {
        Object[] row = VehicleStatusContract.row(null);

        assertEquals(-1, row[0]);
        assertEquals(-1, row[1]);
        assertEquals(0L, row[2]);
        assertEquals("km", row[3]);
        assertNull(row[4]);
    }

    @Test
    public void permissionIsNormalLevelSoAReSignedMapsCanHoldIt() {
        assertEquals("com.bydopenremote.app.permission.READ_VEHICLE", VehicleStatusContract.PERMISSION);
    }

    @Test
    public void authorityAndMimeMatchThePublishedContract() {
        assertEquals("com.bydopenremote.app.vehicle", VehicleStatusContract.AUTHORITY);
        assertEquals("vnd.android.cursor.item/vnd.bydopenremote.vehicle_status",
                VehicleStatusContract.MIME_TYPE);
    }
}
