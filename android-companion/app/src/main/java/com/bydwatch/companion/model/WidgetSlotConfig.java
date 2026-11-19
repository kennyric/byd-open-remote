package com.bydwatch.companion.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for one of the 4 customizable button slots in all Vehicle widgets.
 */
public final class WidgetSlotConfig {

    public static final int SLOT_COUNT = 4;
    public static final int MAX_LABEL_LENGTH = 12;

    public static final String TYPE_SINGLE = "single";
    public static final String TYPE_COMBO = "combo";

    public static final String SINGLE_LOCK = "lock";
    public static final String SINGLE_UNLOCK = "unlock";
    public static final String SINGLE_AC_TOGGLE = "ac_toggle";
    public static final String SINGLE_STATUS = "status";
    public static final String SINGLE_LIGHTS = "lights";
    public static final String SINGLE_HORN = "horn";
    public static final String SINGLE_VENT_WINDOWS = "vent_windows";
    public static final String SINGLE_CLOSE_WINDOWS = "close_windows";
    public static final String SINGLE_BAT_HEAT = "bat_heat_toggle";
    public static final String SINGLE_SEAT_HEAT = "seat_heat";
    public static final String SINGLE_LOCATION = "location";

    public static final List<String> ALL_SINGLE_ACTIONS = Collections.unmodifiableList(Arrays.asList(
            SINGLE_LOCK,
            SINGLE_UNLOCK,
            SINGLE_AC_TOGGLE,
            SINGLE_STATUS,
            SINGLE_LIGHTS,
            SINGLE_HORN,
            SINGLE_VENT_WINDOWS,
            SINGLE_CLOSE_WINDOWS,
            SINGLE_BAT_HEAT,
            SINGLE_SEAT_HEAT,
            SINGLE_LOCATION
    ));

    public final String type;
    public final String targetId;
    public final String customLabel;

    public WidgetSlotConfig(String type, String targetId, String customLabel) {
        this.type = TYPE_COMBO.equals(type) ? TYPE_COMBO : TYPE_SINGLE;
        String rawTarget = targetId != null ? targetId.trim() : "";
        if (TYPE_SINGLE.equals(this.type) && !ALL_SINGLE_ACTIONS.contains(rawTarget)) {
            rawTarget = SINGLE_STATUS;
        }
        this.targetId = rawTarget;
        String label = customLabel != null ? customLabel.trim() : "";
        if (label.length() > MAX_LABEL_LENGTH) {
            label = label.substring(0, MAX_LABEL_LENGTH).trim();
        }
        this.customLabel = label;
    }

    public static WidgetSlotConfig single(String actionKey) {
        return new WidgetSlotConfig(TYPE_SINGLE, actionKey, "");
    }

    public static WidgetSlotConfig combo(String comboId, String customLabel) {
        return new WidgetSlotConfig(TYPE_COMBO, comboId, customLabel);
    }

    public boolean isCombo() {
        return TYPE_COMBO.equals(type) && !targetId.isEmpty();
    }

    public static List<WidgetSlotConfig> defaults() {
        return Arrays.asList(
                single(SINGLE_LOCK),
                single(SINGLE_UNLOCK),
                single(SINGLE_AC_TOGGLE),
                single(SINGLE_STATUS)
        );
    }

    public static List<WidgetSlotConfig> normalizeSlots(List<WidgetSlotConfig> raw) {
        List<WidgetSlotConfig> defaults = defaults();
        List<WidgetSlotConfig> out = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            WidgetSlotConfig item = (raw != null && i < raw.size()) ? raw.get(i) : null;
            out.add(item != null ? item : defaults.get(i));
        }
        return out;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("type", type);
            obj.put("targetId", targetId);
            obj.put("customLabel", customLabel);
        } catch (Exception ignored) {
        }
        return obj;
    }

    public static WidgetSlotConfig fromJson(JSONObject obj) {
        if (obj == null) return null;
        String type = obj.optString("type", TYPE_SINGLE);
        String targetId = obj.optString("targetId", "");
        String customLabel = obj.optString("customLabel", "");
        if (TYPE_COMBO.equals(type) && targetId.trim().isEmpty()) {
            return null;
        }
        return new WidgetSlotConfig(type, targetId, customLabel);
    }

    public static String listToJson(List<WidgetSlotConfig> slots) {
        List<WidgetSlotConfig> normalized = normalizeSlots(slots);
        JSONArray arr = new JSONArray();
        for (WidgetSlotConfig slot : normalized) {
            arr.put(slot.toJson());
        }
        return arr.toString();
    }

    public static List<WidgetSlotConfig> listFromJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new ArrayList<>(defaults());
        }
        List<WidgetSlotConfig> parsed = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && i < SLOT_COUNT; i++) {
                parsed.add(fromJson(arr.optJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return normalizeSlots(parsed);
    }
}
