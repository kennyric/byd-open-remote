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
        assertEquals(4, row.length);
    }

    @Test
    public void missingStatusYieldsUnknownValues() {
        Object[] row = VehicleStatusContract.row(null);

        assertEquals(-1, row[0]);
        assertEquals(-1, row[1]);
        assertEquals(0L, row[2]);
        assertEquals("km", row[3]);
        assertEquals(4, row.length);
    }

    @Test
    public void secondsAreUpscaledToEpochMillis() {
        long seconds = 1_792_922_899L; // the shape the BYD payload actually carries
        VehicleStatus status = new VehicleStatus(32, 84, true, false, null, 22, seconds);

        Object[] row = VehicleStatusContract.row(status);

        assertEquals(seconds * 1000L, ((Number) row[2]).longValue());
        assertEquals(1_792_922_899_000L, VehicleStatusContract.toEpochMillis(seconds));
        assertEquals(1_792_922_899_000L, VehicleStatusContract.toEpochMillis(1_792_922_899_000L));
        assertEquals(0L, VehicleStatusContract.toEpochMillis(0));
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

    @Test
    public void aReportedDistanceReducesSocAtTheCarsOwnConsumption() {
        // 6 km at 15 kWh/100km on a ~45 kWh pack: 0.9 kWh, two points off 36%.
        // With a capacity override the range column is already capacity-based (44.9 × 36 / 15 ≈ 108 km).
        VehicleStatus status = status(36, 300, 15d, 0);
        Object[] row = VehicleStatusContract.row(status, 44.9f, 6d);

        assertEquals(34, row[0]);
        assertEquals(102, row[1]);
        assertEquals(status.ts * 1000L, ((Number) row[2]).longValue());
    }

    @Test
    public void withoutACapacityOverrideTheReadingInfersThePackSize() {
        // 200 km at 50% and 15 kWh/100km implies 60 kWh; 20 km is 5 points.
        VehicleStatus status = status(50, 200, 15d, 0);
        Object[] row = VehicleStatusContract.row(status, 0f, 20d);

        assertEquals(45, row[0]);
        assertEquals(180, row[1]);
    }

    @Test
    public void chargingOrUnknownConsumptionLeavesTheReadingAlone() {
        Object[] charging = VehicleStatusContract.row(status(36, 300, 15d,
                VehicleStatus.CHARGING_ACTIVE), 44.9f, 6d);
        assertEquals(36, charging[0]);

        Object[] plugged = VehicleStatusContract.row(status(36, 300, 15d,
                VehicleStatus.CHARGING_CONNECTED), 44.9f, 6d);
        assertEquals(36, plugged[0]);

        Object[] noConsumption = VehicleStatusContract.row(status(36, 300, -1d, 0), 44.9f, 6d);
        assertEquals(36, noConsumption[0]);
    }

    @Test
    public void theEstimateNeverGoesBelowZeroOrAboveTheReading() {
        Object[] flat = VehicleStatusContract.row(status(3, 20, 15d, 0), 44.9f, 500d);
        assertEquals(0, flat[0]);
        assertEquals(0, flat[1]);

        Object[] backwards = VehicleStatusContract.row(status(36, 300, 15d, 0), 44.9f, -6d);
        assertEquals(36, backwards[0]);
    }

    private static VehicleStatus status(int soc, int rangeKm, double consumption, int charging) {
        return new VehicleStatus.Builder(soc, rangeKm, true, false, null, 22, 1_700_000_000L)
                .recentKwhPer100Km(consumption)
                .chargingState(charging)
                .build();
    }
}
