package com.bydwatch.companion.model;

import com.bydwatch.companion.data.ConfigStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * User-defined multi-step action combo with optional per-step AC and seat climate presets.
 */
public final class CustomActionCombo {

    public static final String ICON_BOLT = "bolt";
    public static final String ICON_FROST = "frost";
    public static final String ICON_SUN = "sun";
    public static final String ICON_SHIELD = "shield";
    public static final String ICON_UNLOCK = "unlock";
    public static final String ICON_BREEZE = "breeze";
    public static final String ICON_SPARKLE = "sparkle";
    public static final String ICON_CAR = "car";

    public static final List<String> PRESET_ICONS = Collections.unmodifiableList(Arrays.asList(
            ICON_BOLT, ICON_FROST, ICON_SUN, ICON_SHIELD,
            ICON_UNLOCK, ICON_BREEZE, ICON_SPARKLE, ICON_CAR
    ));

    public static final String TONE_EMERALD = "emerald";
    public static final String TONE_CYAN = "cyan";
    public static final String TONE_AMBER = "amber";
    public static final String TONE_ROSE = "rose";

    public static final List<String> PRESET_TONES = Collections.unmodifiableList(Arrays.asList(
            TONE_EMERALD, TONE_CYAN, TONE_AMBER, TONE_ROSE
    ));

    public static final int MAX_STEPS = 6;
    public static final int MAX_NAME_LENGTH = 24;

    public final String id;
    public final String name;
    public final String iconKey;
    public final String tone;
    public final List<ComboStep> steps;

    public CustomActionCombo(
            String id,
            String name,
            String iconKey,
            String tone,
            List<ComboStep> steps
    ) {
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : newId();
        String cleanName = name != null ? name.trim() : "";
        if (cleanName.length() > MAX_NAME_LENGTH) {
            cleanName = cleanName.substring(0, MAX_NAME_LENGTH).trim();
        }
        this.name = cleanName.isEmpty() ? "Combo" : cleanName;
        this.iconKey = normalizeIconKey(iconKey);
        this.tone = normalizeTone(tone);
        this.steps = steps != null
                ? Collections.unmodifiableList(new ArrayList<>(steps))
                : Collections.emptyList();
    }

    public static String newId() {
        return "cmb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String normalizeIconKey(String raw) {
        if (raw == null) return ICON_BOLT;
        String clean = raw.trim().toLowerCase(java.util.Locale.US);
        return PRESET_ICONS.contains(clean) ? clean : ICON_BOLT;
    }

    public static String normalizeTone(String raw) {
        if (raw == null) return TONE_AMBER;
        String clean = raw.trim().toLowerCase(java.util.Locale.US);
        return PRESET_TONES.contains(clean) ? clean : TONE_AMBER;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("name", name);
            obj.put("iconKey", iconKey);
            obj.put("tone", tone);
            JSONArray arr = new JSONArray();
            for (ComboStep step : steps) {
                if (step != null) {
                    arr.put(step.toJson());
                }
            }
            obj.put("steps", arr);
        } catch (Exception ignored) {
        }
        return obj;
    }

    public static CustomActionCombo fromJson(JSONObject obj) {
        if (obj == null) return null;
        String id = obj.optString("id", "");
        String name = obj.optString("name", "");
        String iconKey = obj.optString("iconKey", ICON_BOLT);
        String tone = obj.optString("tone", TONE_AMBER);
        JSONArray arr = obj.optJSONArray("steps");
        List<ComboStep> parsedSteps = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length() && parsedSteps.size() < MAX_STEPS; i++) {
                ComboStep step = ComboStep.fromJson(arr.optJSONObject(i));
                if (step != null) {
                    parsedSteps.add(step);
                }
            }
        }
        if (parsedSteps.isEmpty()) {
            return null;
        }
        return new CustomActionCombo(id, name, iconKey, tone, parsedSteps);
    }

    public static String listToJson(List<CustomActionCombo> combos) {
        JSONArray arr = new JSONArray();
        if (combos != null) {
            for (CustomActionCombo c : combos) {
                if (c != null) {
                    arr.put(c.toJson());
                }
            }
        }
        return arr.toString();
    }

    public static List<CustomActionCombo> listFromJson(String raw) {
        List<CustomActionCombo> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                CustomActionCombo combo = fromJson(arr.optJSONObject(i));
                if (combo != null) {
                    result.add(combo);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * Single step inside a {@link CustomActionCombo}.
     */
    public static final class ComboStep {
        public final String action;
        public final boolean useCustomAc;
        public final boolean manualAc;
        public final int tempC;
        public final int tempGear;
        public final int fanLevel;
        public final int windMode;
        public final int seatLevel;
        public final int acDurationMinutes;

        public ComboStep(String action) {
            this(action, true, false, 20, 3, 4, 1, -1, ConfigStore.DEFAULT_AC_DURATION_MINUTES);
        }

        public ComboStep(
                String action,
                boolean useCustomAc,
                boolean manualAc,
                int tempC,
                int tempGear,
                int fanLevel,
                int windMode,
                int seatLevel
        ) {
            this(action, useCustomAc, manualAc, tempC, tempGear, fanLevel, windMode, seatLevel,
                    ConfigStore.DEFAULT_AC_DURATION_MINUTES);
        }

        public ComboStep(
                String action,
                boolean useCustomAc,
                boolean manualAc,
                int tempC,
                int tempGear,
                int fanLevel,
                int windMode,
                int seatLevel,
                int acDurationMinutes
        ) {
            this.action = action != null ? action.trim() : "";
            this.useCustomAc = useCustomAc;
            this.manualAc = manualAc;
            this.tempC = Math.max(17, Math.min(31, tempC));
            this.tempGear = Math.max(1, Math.min(9, tempGear));
            this.fanLevel = Math.max(1, Math.min(7, fanLevel));
            this.windMode = (windMode == 1 || windMode == 2 || windMode == 3) ? windMode : 1;
            this.seatLevel = (seatLevel >= 0 && seatLevel <= 2) ? seatLevel : -1;
            this.acDurationMinutes = ConfigStore.normalizeAcDurationMinutes(acDurationMinutes);
        }

        public ComboStep copy() {
            return new ComboStep(action, useCustomAc, manualAc, tempC, tempGear, fanLevel,
                    windMode, seatLevel, acDurationMinutes);
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("action", action);
                obj.put("useCustomAc", useCustomAc);
                obj.put("manualAc", manualAc);
                obj.put("tempC", tempC);
                obj.put("tempGear", tempGear);
                obj.put("fanLevel", fanLevel);
                obj.put("windMode", windMode);
                obj.put("seatLevel", seatLevel);
                obj.put("acDurationMin", acDurationMinutes);
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static ComboStep fromJson(JSONObject obj) {
            if (obj == null) return null;
            String act = obj.optString("action", "").trim();
            if (act.isEmpty()) return null;
            return new ComboStep(
                    act,
                    obj.optBoolean("useCustomAc", true),
                    obj.optBoolean("manualAc", false),
                    obj.optInt("tempC", 20),
                    obj.optInt("tempGear", 3),
                    obj.optInt("fanLevel", 4),
                    obj.optInt("windMode", 1),
                    obj.optInt("seatLevel", -1),
                    obj.optInt("acDurationMin", ConfigStore.DEFAULT_AC_DURATION_MINUTES)
            );
        }
    }
}
