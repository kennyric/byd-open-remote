package com.bydwatch.companion.protocol;

import com.bydwatch.companion.model.VehicleStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Protocol parser and serializer for the Huawei Watch GT 4 <-> Android Companion JSON contract.
 */
public final class WatchProtocol {

    public static final int MIN_TEMP_C = 17;
    public static final int MAX_TEMP_C = 31;
    public static final int DEFAULT_TEMP_C = 17;

    public static final String ACTION_STATUS = "status";
    public static final String ACTION_LOCK = "lock";
    public static final String ACTION_UNLOCK = "unlock";
    public static final String ACTION_AC_ON = "ac_on";
    public static final String ACTION_AC_OFF = "ac_off";
    public static final String ACTION_LIGHTS = "lights";
    public static final String ACTION_HORN = "horn";
    public static final String ACTION_VENT_WINDOWS = "vent_windows";
    public static final String ACTION_CLOSE_WINDOWS = "close_windows";
    public static final String ACTION_BATTERY_HEAT_ON = "bat_heat_on";
    public static final String ACTION_BATTERY_HEAT_OFF = "bat_heat_off";
    public static final String ACTION_SEAT_HEAT = "seat_heat";
    public static final String ACTION_SWITCH_CAR = "switch_car";
    public static final String ACTION_LOCATION = "location";

    private static final Set<String> VALID_ACTIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    ACTION_STATUS,
                    ACTION_LOCK,
                    ACTION_UNLOCK,
                    ACTION_AC_ON,
                    ACTION_AC_OFF,
                    ACTION_LIGHTS,
                    ACTION_HORN,
                    ACTION_VENT_WINDOWS,
                    ACTION_CLOSE_WINDOWS,
                    ACTION_BATTERY_HEAT_ON,
                    ACTION_BATTERY_HEAT_OFF,
                    ACTION_SEAT_HEAT,
                    ACTION_SWITCH_CAR,
                    ACTION_LOCATION
            ))
    );

    private WatchProtocol() {}

    /**
     * Parse and strictly validate an incoming JSON command from the watch.
     *
     * @param rawJson JSON string sent over Wear Engine P2P.
     * @return Validated WatchCommand.
     * @throws IllegalArgumentException on malformed JSON, missing fields, or unknown action.
     */
    public static WatchCommand parseCommand(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Payload cannot be null or empty");
        }

        JSONObject obj;
        try {
            obj = new JSONObject(rawJson);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Malformed JSON: " + e.getMessage(), e);
        }

        if (!obj.has("id") || obj.isNull("id")) {
            throw new IllegalArgumentException("Missing required 'id' field");
        }
        String id = obj.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("'id' field cannot be empty");
        }

        if (!obj.has("action") || obj.isNull("action")) {
            throw new IllegalArgumentException("Missing required 'action' field");
        }
        String action = obj.optString("action", "").trim();
        if (!VALID_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unknown action: '" + action + "'");
        }

        int tempC = DEFAULT_TEMP_C;
        if (obj.has("tempC") && !obj.isNull("tempC")) {
            int parsed = obj.optInt("tempC", DEFAULT_TEMP_C);
            if (parsed < MIN_TEMP_C) {
                tempC = MIN_TEMP_C;
            } else if (parsed > MAX_TEMP_C) {
                tempC = MAX_TEMP_C;
            } else {
                tempC = parsed;
            }
        }

        String vin = obj.optString("vin", "").trim();
        int level = obj.has("level") && !obj.isNull("level") ? obj.optInt("level", -1) : -1;

        return new WatchCommand(id, action, tempC, vin, level);
    }

    /**
     * Serialize a success response to JSON string.
     */
    public static String ok(String id, String action, String msg, VehicleStatus state) {
        return WatchResponse.ok(id, action, msg, state).toJsonString();
    }

    /**
     * Serialize an error response to JSON string.
     */
    public static String error(String id, String action, String errCode, String msg) {
        return WatchResponse.error(id, action, errCode, msg).toJsonString();
    }
}
