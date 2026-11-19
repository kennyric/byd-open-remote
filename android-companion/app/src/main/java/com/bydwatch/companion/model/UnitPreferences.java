package com.bydwatch.companion.model;

import java.util.Locale;

/**
 * Canonical metric-to-display conversion helpers for distance, temperature, and tire pressure.
 */
public final class UnitPreferences {

    public static final String DIST_KM = "km";
    public static final String DIST_MI = "mi";

    public static final String TEMP_C = "C";
    public static final String TEMP_F = "F";

    public static final String PRES_KPA = "kPa";
    public static final String PRES_PSI = "PSI";
    public static final String PRES_BAR = "bar";

    private UnitPreferences() {}

    public static String normalizeDistanceUnit(String raw) {
        if (raw != null && DIST_MI.equalsIgnoreCase(raw.trim())) {
            return DIST_MI;
        }
        return DIST_KM;
    }

    public static String normalizeTempUnit(String raw) {
        if (raw != null && TEMP_F.equalsIgnoreCase(raw.trim())) {
            return TEMP_F;
        }
        return TEMP_C;
    }

    public static String normalizePressureUnit(String raw) {
        if (raw == null) return PRES_KPA;
        String t = raw.trim();
        if (PRES_PSI.equalsIgnoreCase(t)) return PRES_PSI;
        if (PRES_BAR.equalsIgnoreCase(t)) return PRES_BAR;
        return PRES_KPA;
    }

    public static int convertDistance(int km, String unit) {
        if (DIST_MI.equals(normalizeDistanceUnit(unit))) {
            return (int) Math.round(km * 0.621371);
        }
        return km;
    }

    public static String formatDistance(int km, String unit) {
        String u = normalizeDistanceUnit(unit);
        return convertDistance(km, u) + " " + u;
    }

    public static int celsiusToFahrenheit(int tempC) {
        return (int) Math.round(tempC * 9.0 / 5.0 + 32.0);
    }

    public static int fahrenheitToCelsius(int tempF) {
        return (int) Math.round((tempF - 32.0) * 5.0 / 9.0);
    }

    public static String formatTemp(int tempC, String unit) {
        String u = normalizeTempUnit(unit);
        if (TEMP_F.equals(u)) {
            return celsiusToFahrenheit(tempC) + "°F";
        }
        return tempC + "°C";
    }

    public static String formatPressure(Integer kpa, String unit) {
        if (kpa == null || kpa <= 0) {
            return "-- " + normalizePressureUnit(unit);
        }
        String u = normalizePressureUnit(unit);
        if (PRES_PSI.equals(u)) {
            double psi = kpa * 0.1450377;
            return String.format(Locale.US, "%.1f PSI", psi);
        }
        if (PRES_BAR.equals(u)) {
            double bar = kpa / 100.0;
            return String.format(Locale.US, "%.2f bar", bar);
        }
        return kpa + " kPa";
    }

    /**
     * Normalize BYD TPMS values to integer kPa.
     * BYD reports raw TPMS depending on regional/vehicle settings:
     * - 1.0–9.9: bar (e.g. 2.45 -> 245 kPa)
     * - 10.0–99.9: PSI (e.g. 39 -> 269 kPa)
     * - 100.0–600.0: kPa (e.g. 245 -> 245 kPa)
     */
    public static Integer normalizeRawTpmsToKpa(double raw) {
        if (!Double.isFinite(raw) || raw <= 0.0) {
            return null;
        }
        if (raw < 10.0) {
            // Reported in bar (e.g. 2.45)
            return (int) Math.round(raw * 100.0);
        }
        if (raw < 100.0) {
            // Reported in PSI (e.g. 39 PSI -> 269 kPa)
            return (int) Math.round(raw * 6.894757);
        }
        if (raw <= 600.0) {
            return (int) Math.round(raw);
        }
        return null;
    }
}
