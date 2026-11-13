package com.bydwatch.companion.protocol;

import com.bydwatch.companion.model.VehicleStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Structured response sent back to the Huawei Watch GT 4 companion app.
 */
public final class WatchResponse {

    public final String id;
    public final boolean ok;
    public final String action;
    public final String msg;
    public final VehicleStatus state;
    public final String err;

    public WatchResponse(
            String id,
            boolean ok,
            String action,
            String msg,
            VehicleStatus state,
            String err) {
        this.id = id != null ? id : "unknown";
        this.ok = ok;
        this.action = action != null ? action : "unknown";
        this.msg = msg != null ? msg : "";
        this.state = state;
        this.err = err;
    }

    public static WatchResponse ok(String id, String action, String msg, VehicleStatus state) {
        return new WatchResponse(id, true, action, msg, state, null);
    }

    public static WatchResponse error(String id, String action, String errCode, String msg) {
        return new WatchResponse(id, false, action, msg, null, errCode != null ? errCode : "ERROR");
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("ok", ok);
            obj.put("action", action);
            if (ok) {
                obj.put("msg", msg);
                if (state != null) {
                    obj.put("state", state.toJson());
                }
            } else {
                obj.put("err", err != null ? err : "ERROR");
                obj.put("msg", msg);
            }
        } catch (JSONException e) {
            throw new RuntimeException("Failed to serialize WatchResponse", e);
        }
        return obj;
    }

    public String toJsonString() {
        return toJson().toString();
    }

    public static WatchResponse fromJson(JSONObject json) {
        if (json == null) return null;
        String id = json.optString("id", "unknown");
        boolean ok = json.optBoolean("ok", false);
        String action = json.optString("action", "unknown");
        String msg = json.optString("msg", "");
        String err = json.optString("err", null);
        JSONObject stateObj = json.optJSONObject("state");
        VehicleStatus state = stateObj != null ? VehicleStatus.fromJson(stateObj) : null;
        return new WatchResponse(id, ok, action, msg, state, err);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WatchResponse that = (WatchResponse) o;
        return ok == that.ok
                && Objects.equals(id, that.id)
                && Objects.equals(action, that.action)
                && Objects.equals(msg, that.msg)
                && Objects.equals(state, that.state)
                && Objects.equals(err, that.err);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, ok, action, msg, state, err);
    }

    @Override
    public String toString() {
        return "WatchResponse{" +
                "id='" + id + '\'' +
                ", ok=" + ok +
                ", action='" + action + '\'' +
                ", msg='" + msg + '\'' +
                ", state=" + state +
                ", err='" + err + '\'' +
                '}';
    }
}
