package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;

/**
 * Distance a consumer (the patched Maps) reports since the reading it last saw.
 *
 * <p>Kept in memory only: it is a hint for one display estimate, not data worth surviving a
 * process restart. A new reading timestamp starts a new anchor, so a real reading always wins.
 */
final class ConsumerProgress {

    private static long anchorTs;
    private static double distanceKm;

    private ConsumerProgress() {}

    /** Adds a reported gap; ignores junk so a bad consumer cannot invent consumption. */
    static synchronized void report(VehicleStatus reading, double gapKm) {
        if (reading == null || !Double.isFinite(gapKm) || gapKm <= 0d) return;
        long ts = VehicleStatusContract.toEpochMillis(reading.ts);
        if (ts <= 0L) return;
        if (ts != anchorTs) {
            anchorTs = ts;
            distanceKm = 0d;
        }
        double capped = Math.min(gapKm, VehicleStatusContract.MAX_ESTIMATE_DISTANCE_KM);
        distanceKm = Math.min(VehicleStatusContract.MAX_ESTIMATE_DISTANCE_KM, distanceKm + capped);
    }

    /** Distance reported for exactly this reading, or 0 for another or unknown reading. */
    static synchronized double since(VehicleStatus reading) {
        if (reading == null) return 0d;
        long ts = VehicleStatusContract.toEpochMillis(reading.ts);
        return ts > 0L && ts == anchorTs ? distanceKm : 0d;
    }

    static synchronized void resetForTesting() {
        anchorTs = 0L;
        distanceKm = 0d;
    }
}
