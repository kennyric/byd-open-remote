package com.bydwatch.companion.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Lightweight descriptor of a BYD vehicle discovered on the user's account.
 */
public final class VehicleSummary {

    public static final String DEFAULT_MODEL_NAME = "BYD EV";

    public final String vin;
    public final String modelName;
    public final String plateNo;
    public final String energyType;

    public VehicleSummary(String vin, String modelName, String plateNo, String energyType) {
        this.vin = vin != null ? vin.trim() : "";
        this.modelName = (modelName != null && !modelName.trim().isEmpty())
                ? modelName.trim()
                : DEFAULT_MODEL_NAME;
        this.plateNo = plateNo != null ? plateNo.trim() : "";
        this.energyType = energyType != null ? energyType.trim() : "";
    }

    public static VehicleSummary fromBydVehicleJson(JSONObject vehicle) {
        if (vehicle == null) return null;
        String vin = firstNonEmpty(vehicle, "vin");
        if (vin.isEmpty()) return null;

        String rawModel = extractModelFromJson(vehicle);
        String modelName = normalizeModelName(rawModel);
        String plateNo = firstNonEmpty(vehicle, "plateNo", "carNo", "licensePlate");
        String energyType = firstNonEmpty(vehicle, "energyType");
        return new VehicleSummary(vin, modelName, plateNo, energyType);
    }

    /**
     * Extract a human-readable BYD model name from BYD account/telemetry JSON payloads,
     * preferring descriptive fields over purely numeric codes such as autoType="1".
     */
    public static String extractModelFromJson(JSONObject obj) {
        if (obj == null) return "";
        String[] descriptiveKeys = {
                "modelName",
                "carModelName",
                "seriesName",
                "carSeriesName",
                "modelDesc",
                "vehicleName",
                "carName",
                "autoModelName",
                "vehicleModel",
                "carModel",
                "autoAlias",
                "model",
                "autoType",
                "brandName",
                "brand"
        };
        String numericFallback = "";
        for (String key : descriptiveKeys) {
            String candidate = firstNonEmpty(obj, key);
            if (candidate.isEmpty()) continue;
            if (candidate.matches("^\\d+$")) {
                if (numericFallback.isEmpty() && !"autoType".equals(key)) {
                    numericFallback = candidate;
                }
                continue;
            }
            return candidate;
        }
        return numericFallback;
    }

    public static boolean isGenericModelName(String raw) {
        if (raw == null) return true;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return true;
        if ("null".equalsIgnoreCase(cleaned) || "-".equals(cleaned) || "--".equals(cleaned)) {
            return true;
        }
        if (cleaned.matches("^\\d+$")) return true;
        return DEFAULT_MODEL_NAME.equalsIgnoreCase(cleaned)
                || "EV".equalsIgnoreCase(cleaned)
                || "BYD".equalsIgnoreCase(cleaned);
    }

    /**
     * Format a displayable model title such as "BYD Atto 3" when a specific model is known,
     * falling back to "BYD EV" when unknown.
     */
    public static String formatDisplayModel(String raw) {
        if (isGenericModelName(raw)) {
            return DEFAULT_MODEL_NAME;
        }
        String cleaned = normalizeModelName(raw);
        String upper = cleaned.toUpperCase(Locale.US);
        if (upper.startsWith("BYD ") || upper.startsWith("BYD-") || upper.contains("BYD")) {
            return cleaned;
        }
        return "BYD " + cleaned;
    }

    public static String normalizeModelName(String raw) {
        if (raw == null) return DEFAULT_MODEL_NAME;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return DEFAULT_MODEL_NAME;
        return cleaned;
    }

    /**
     * Compact uppercase title suitable for the Watch GT 4 top badge (e.g. "ATTO 3", "SEAL AWD").
     */
    public String watchShortLabel() {
        String label = modelName != null ? modelName.trim() : DEFAULT_MODEL_NAME;
        if (label.toUpperCase().startsWith("BYD ") && label.length() > 4) {
            label = label.substring(4).trim();
        }
        if (label.length() > 14) {
            label = label.substring(0, 14).trim();
        }
        return label.isEmpty() ? "EV REMOTE" : label.toUpperCase();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("vin", vin);
            obj.put("name", modelName);
            if (!plateNo.isEmpty()) obj.put("plate", plateNo);
            if (!energyType.isEmpty()) obj.put("energy", energyType);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to serialize VehicleSummary", e);
        }
        return obj;
    }

    public static VehicleSummary fromJson(JSONObject obj) {
        if (obj == null) return null;
        String vin = obj.optString("vin", "").trim();
        if (vin.isEmpty()) return null;
        String name = obj.optString("name", obj.optString("modelName", DEFAULT_MODEL_NAME));
        String plate = obj.optString("plate", obj.optString("plateNo", ""));
        String energy = obj.optString("energy", obj.optString("energyType", ""));
        return new VehicleSummary(vin, name, plate, energy);
    }

    public static String toJsonArrayString(List<VehicleSummary> vehicles) {
        JSONArray arr = new JSONArray();
        if (vehicles != null) {
            for (VehicleSummary v : vehicles) {
                if (v != null && !v.vin.isEmpty()) {
                    arr.put(v.toJson());
                }
            }
        }
        return arr.toString();
    }

    public static List<VehicleSummary> fromJsonArrayString(String raw) {
        List<VehicleSummary> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                VehicleSummary v = fromJson(arr.optJSONObject(i));
                if (v != null) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String firstNonEmpty(JSONObject obj, String... keys) {
        for (String k : keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                String v = obj.optString(k, "").trim();
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                    return v;
                }
            }
        }
        return "";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VehicleSummary)) return false;
        VehicleSummary that = (VehicleSummary) o;
        return Objects.equals(vin, that.vin)
                && Objects.equals(modelName, that.modelName)
                && Objects.equals(plateNo, that.plateNo)
                && Objects.equals(energyType, that.energyType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vin, modelName, plateNo, energyType);
    }

    @Override
    public String toString() {
        if (plateNo != null && !plateNo.isEmpty()) {
            return modelName + " (" + plateNo + ")";
        }
        if (vin.length() > 4) {
            return modelName + " (••••" + vin.substring(vin.length() - 4) + ")";
        }
        return modelName;
    }
}
