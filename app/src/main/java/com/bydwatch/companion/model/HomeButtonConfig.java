package com.bydwatch.companion.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuration for a user-added custom button on the app homepage.
 * Supports either a single BYD remote action or a multi-step CustomActionCombo.
 */
public final class HomeButtonConfig {

    public static final int MAX_BUTTONS = 12;
    public static final int MAX_LABEL_LENGTH = 18;

    public static final String TYPE_SINGLE = WidgetSlotConfig.TYPE_SINGLE;
    public static final String TYPE_COMBO = WidgetSlotConfig.TYPE_COMBO;

    public final String id;
    public final String type;
    public final String targetId;
    public final String label;
    public final String iconKey;
    public final String tone;

    public HomeButtonConfig(
            String id,
            String type,
            String targetId,
            String label,
            String iconKey,
            String tone
    ) {
        this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : newId();
        this.type = TYPE_COMBO.equals(type) ? TYPE_COMBO : TYPE_SINGLE;
        String cleanTarget = targetId != null ? targetId.trim() : "";
        if (TYPE_SINGLE.equals(this.type) && !WidgetSlotConfig.ALL_SINGLE_ACTIONS.contains(cleanTarget)) {
            cleanTarget = WidgetSlotConfig.SINGLE_STATUS;
        }
        this.targetId = cleanTarget;
        String cleanLabel = label != null ? label.trim() : "";
        if (cleanLabel.length() > MAX_LABEL_LENGTH) {
            cleanLabel = cleanLabel.substring(0, MAX_LABEL_LENGTH).trim();
        }
        this.label = cleanLabel;
        this.iconKey = (iconKey != null && !iconKey.trim().isEmpty())
                ? CustomActionCombo.normalizeIconKey(iconKey)
                : "";
        this.tone = (tone != null && !tone.trim().isEmpty())
                ? CustomActionCombo.normalizeTone(tone)
                : "";
    }

    public static String newId() {
        return "hbtn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public boolean isCombo() {
        return TYPE_COMBO.equals(type) && !targetId.isEmpty();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("type", type);
            obj.put("targetId", targetId);
            obj.put("label", label);
            obj.put("iconKey", iconKey);
            obj.put("tone", tone);
        } catch (Exception ignored) {
        }
        return obj;
    }

    public static HomeButtonConfig fromJson(JSONObject obj) {
        if (obj == null) return null;
        String id = obj.optString("id", "");
        String type = obj.optString("type", TYPE_SINGLE);
        String targetId = obj.optString("targetId", "");
        if (TYPE_COMBO.equals(type) && targetId.trim().isEmpty()) {
            return null;
        }
        return new HomeButtonConfig(
                id,
                type,
                targetId,
                obj.optString("label", ""),
                obj.optString("iconKey", ""),
                obj.optString("tone", "")
        );
    }

    public static String listToJson(List<HomeButtonConfig> buttons) {
        JSONArray arr = new JSONArray();
        if (buttons != null) {
            int count = 0;
            for (HomeButtonConfig b : buttons) {
                if (b != null) {
                    arr.put(b.toJson());
                    count++;
                    if (count >= MAX_BUTTONS) break;
                }
            }
        }
        return arr.toString();
    }

    public static List<HomeButtonConfig> listFromJson(String json) {
        List<HomeButtonConfig> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && out.size() < MAX_BUTTONS; i++) {
                HomeButtonConfig item = fromJson(arr.optJSONObject(i));
                if (item != null) {
                    out.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
