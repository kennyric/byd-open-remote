package com.bydwatch.companion.lab;

import com.bydwatch.companion.model.VehicleSummary;

import java.util.Collections;
import java.util.List;

/** Resolves the active vehicle's drivetrain type for the AC current-limit call. */
public final class LabContext {

    private LabContext() {}

    public static String rawEnergyType(String activeVin, List<VehicleSummary> vehicles) {
        VehicleSummary vehicle = find(activeVin, safe(vehicles));
        return vehicle != null && vehicle.energyType != null ? vehicle.energyType.trim() : "";
    }

    /**
     * Prefer the active car's energyType. A non-empty vehicle list that does not
     * contain the active VIN is not trusted, because its first entry may belong
     * to another car; in that case use the EV default. An empty list may use the
     * caller's fallback.
     */
    public static String resolveEnergyType(String activeVin,
                                           List<VehicleSummary> vehicles,
                                           String fallback) {
        List<VehicleSummary> list = safe(vehicles);
        VehicleSummary active = find(activeVin, list);
        if (active != null) {
            String raw = active.energyType != null ? active.energyType.trim() : "";
            if (isValid(raw)) return raw;
            return isValid(fallback) ? fallback.trim() : "0";
        }
        if (list.isEmpty() && isValid(fallback)) return fallback.trim();
        return "0";
    }

    private static VehicleSummary find(String activeVin, List<VehicleSummary> vehicles) {
        if (activeVin == null || activeVin.trim().isEmpty()) return null;
        for (VehicleSummary vehicle : vehicles) {
            if (vehicle != null && activeVin.equals(vehicle.vin)) return vehicle;
        }
        return null;
    }

    private static boolean isValid(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return "0".equals(trimmed) || "2".equals(trimmed);
    }

    private static List<VehicleSummary> safe(List<VehicleSummary> vehicles) {
        return vehicles != null ? vehicles : Collections.emptyList();
    }
}
