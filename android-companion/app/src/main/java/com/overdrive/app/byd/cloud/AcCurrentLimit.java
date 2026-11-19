package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Parsed response from {@code /control/smartCharge/getAcCurrentLimit}.
 *
 * <p>The cloud returns the active AC charging current limit and the list of
 * values the vehicle supports, for example
 * {@code ["6A","8A","10A","16A","MAX"]}. Values are kept as the cloud spelled
 * them; {@link ChargeLimitValues} handles comparison.
 */
public final class AcCurrentLimit {

    public final String vin;
    /** Active limit, or null when the cloud did not report one. */
    public final String active;
    /** Supported limits, order preserved, duplicates removed. */
    public final List<String> options;
    /** Raw decrypted payload, for diagnostics. */
    public final JSONObject raw;

    public AcCurrentLimit(String vin, String active, List<String> options, JSONObject raw) {
        this.vin = vin != null ? vin : "";
        this.active = active;
        this.options = options != null
                ? Collections.unmodifiableList(new ArrayList<>(options))
                : Collections.emptyList();
        this.raw = raw != null ? raw : new JSONObject();
    }

    public boolean hasActive() {
        return active != null && !active.isEmpty();
    }

    public static AcCurrentLimit parse(String vin, JSONObject payload) {
        JSONObject safe = payload != null ? payload : new JSONObject();
        String active = normalize(safe.opt("currentEvCurrentLimit"));
        List<String> options = new ArrayList<>();
        JSONArray array = safe.optJSONArray("currentLimitList");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String value = normalize(array.opt(i));
                if (!value.isEmpty() && !options.contains(value)) {
                    options.add(value);
                }
            }
        }
        return new AcCurrentLimit(vin, active.isEmpty() ? null : active, options, safe);
    }

    private static String normalize(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        String text = String.valueOf(value).trim().toUpperCase(Locale.US);
        if (text.isEmpty()
                || "-".equals(text)
                || "--".equals(text)
                || "NULL".equals(text)
                || "UNKNOWN".equals(text)) {
            return "";
        }
        return text;
    }
}
