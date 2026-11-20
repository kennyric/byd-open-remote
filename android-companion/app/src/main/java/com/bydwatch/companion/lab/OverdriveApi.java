package com.bydwatch.companion.lab;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Shapes and parses the car's Overdrive HTTP API.
 *
 * <p>Pure request/response helpers: no Android context, no threading. The
 * activity layer owns the network calls. See
 * {@code docs/superpowers/specs/2026-11-20-byd-overdrive-native-controls-design.md}.
 */
public final class OverdriveApi {

    /** How the schedule repeats on the car. */
    public enum Repeat { ONCE, DAILY, WEEKDAYS }

    /** Weekday indexes Mon=0 … Sun=6, as Overdrive's {@code chargeWay} expects. */
    public static final String WEEKDAY_CHARGE_WAY = "0,1,2,3,4";

    public static final int STATE_6A = 1;
    public static final int STATE_8A = 2;
    public static final int STATE_10A = 3;
    public static final int STATE_16A = 4;
    public static final int STATE_MAX = 5;

    private static final String[] STATE_LABELS = {"6 A", "8 A", "10 A", "16 A", "Max"};

    /** AC current limit as reported by the car. {@code state == 0} means unknown. */
    public static final class CurrentLimit {
        public final boolean supported;
        public final int state;
        public final String label;

        CurrentLimit(boolean supported, int state, String label) {
            this.supported = supported;
            this.state = state;
            this.label = label;
        }
    }

    /** Charging schedule as reported by the car. */
    public static final class Schedule {
        public final boolean supported;
        public final boolean enabled;
        public final String start;
        public final String end;
        public final Repeat repeat;
        /** Raw {@code chargeWay} so an untouched weekday set survives a save. */
        public final String chargeWay;
        /** {@code cloud} or {@code cache} — where Overdrive read the value. */
        public final String source;

        Schedule(boolean supported, boolean enabled, String start, String end,
                 Repeat repeat, String chargeWay, String source) {
            this.supported = supported;
            this.enabled = enabled;
            this.start = start;
            this.end = end;
            this.repeat = repeat;
            this.chargeWay = chargeWay;
            this.source = source;
        }

        public boolean endIsFull() {
            return end != null && "full".equalsIgnoreCase(end.trim());
        }

        public String endLabel() {
            return endIsFull() ? "Full" : end;
        }
    }

    private OverdriveApi() {}

    private static String url(String base, String path) {
        String normalized = CarWebUrl.normalize(base);
        if (normalized.isEmpty()) return "";
        return normalized.endsWith("/") ? normalized + path : normalized + "/" + path;
    }

    public static String authStatusUrl(String base) {
        return url(base, "auth/status");
    }

    public static String tokenUrl(String base) {
        return url(base, "auth/token");
    }

    public static String currentLimitUrl(String base) {
        return url(base, "api/vehicle/ac-charge-current-limit");
    }

    public static String scheduleUrl(String base) {
        return url(base, "api/vehicle/charging-schedule");
    }

    public static String startChargingUrl(String base) {
        return url(base, "api/vehicle/start-charging");
    }

    /** Overdrive's device token is {@code <deviceId>-<accessCode>}. */
    public static String deviceToken(String deviceId, String accessCode) {
        String id = deviceId != null ? deviceId.trim() : "";
        String code = accessCode != null
                ? accessCode.trim().toLowerCase(Locale.ROOT) : "";
        return id + "-" + code;
    }

    public static String parseDeviceId(String json) {
        if (json == null) return null;
        try {
            String id = new JSONObject(json).optString("deviceId", "").trim();
            if (id.isEmpty() || "unknown".equalsIgnoreCase(id)) return null;
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    public static String parseJwt(String json) {
        if (json == null) return null;
        try {
            JSONObject object = new JSONObject(json);
            if (!object.optBoolean("success", false)) return null;
            String jwt = object.optString("jwt", "").trim();
            return jwt.isEmpty() ? null : jwt;
        } catch (Exception e) {
            return null;
        }
    }

    public static String labelForState(int state) {
        if (state < STATE_6A || state > STATE_MAX) return null;
        return STATE_LABELS[state - 1];
    }

    public static int stateForLabel(String label) {
        if (label == null) return 0;
        String wanted = label.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        for (int i = 0; i < STATE_LABELS.length; i++) {
            if (STATE_LABELS[i].replace(" ", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static CurrentLimit parseCurrentLimit(String json) {
        if (json == null) return new CurrentLimit(false, 0, null);
        try {
            JSONObject object = new JSONObject(json);
            boolean supported = object.optBoolean("supported", false);
            int state = object.isNull("state") ? 0 : object.optInt("state", 0);
            String label = labelForState(state);
            if (label == null) label = object.isNull("label") ? null : object.optString("label", null);
            return new CurrentLimit(supported, state, label);
        } catch (Exception e) {
            return new CurrentLimit(false, 0, null);
        }
    }

    public static Schedule parseSchedule(String json) {
        if (json == null) return new Schedule(false, false, null, null, null, null, null);
        try {
            JSONObject object = new JSONObject(json);
            String start = object.isNull("startChargeTime")
                    ? null : object.optString("startChargeTime", null);
            String end = object.isNull("endChargeTime")
                    ? null : object.optString("endChargeTime", null);
            String way = object.isNull("chargeWay") ? null : object.optString("chargeWay", null);
            String source = object.isNull("source") ? null : object.optString("source", null);
            return new Schedule(
                    object.optBoolean("supported", false),
                    object.optBoolean("enabled", false),
                    start,
                    end,
                    repeatFor(way),
                    way,
                    source);
        } catch (Exception e) {
            return new Schedule(false, false, null, null, null, null, null);
        }
    }

    public static String chargeWayFor(Repeat repeat) {
        if (repeat == null) return "e";
        switch (repeat) {
            case ONCE:
                return "s";
            case WEEKDAYS:
                return WEEKDAY_CHARGE_WAY;
            case DAILY:
            default:
                return "e";
        }
    }

    public static Repeat repeatFor(String chargeWay) {
        if (chargeWay == null) return null;
        String way = chargeWay.trim();
        if (way.isEmpty()) return null;
        if ("s".equals(way)) return Repeat.ONCE;
        if ("e".equals(way)) return Repeat.DAILY;
        for (int i = 0; i < way.length(); i++) {
            char c = way.charAt(i);
            if (c != ',' && (c < '0' || c > '6')) return null;
        }
        return Repeat.WEEKDAYS;
    }

    public static String scheduleBodyJson(String start, String end, Repeat repeat, boolean enabled) {
        JSONObject body = new JSONObject();
        try {
            body.put("startChargeTime", start);
            body.put("endChargeTime", end);
            body.put("chargeWay", chargeWayFor(repeat));
            body.put("enabled", enabled);
        } catch (Exception ignored) {
            // JSONObject.put only rejects non-finite doubles; strings and booleans are safe.
        }
        return body.toString();
    }

    /** A 401/403 means the stored Overdrive session is gone, not a transport failure. */
    public static boolean isAuthFailure(int httpCode) {
        return httpCode == 401 || httpCode == 403;
    }
}
