package com.bydwatch.companion.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * Immutable telemetry snapshot representing vehicle state sent to the watch and displayed in UI.
 */
public final class VehicleStatus {

    public final int soc;
    public final int rangeKm;
    public final boolean locked;
    public final boolean acOn;
    public final Integer cabinTempC;
    public final int targetTempC;
    public final long ts;

    public VehicleStatus(
            int soc,
            int rangeKm,
            boolean locked,
            boolean acOn,
            Integer cabinTempC,
            int targetTempC,
            long ts) {
        this.soc = soc;
        this.rangeKm = rangeKm;
        this.locked = locked;
        this.acOn = acOn;
        this.cabinTempC = cabinTempC;
        this.targetTempC = targetTempC;
        this.ts = ts;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("soc", soc);
            obj.put("rangeKm", rangeKm);
            obj.put("locked", locked);
            obj.put("acOn", acOn);
            obj.put("cabinTempC", cabinTempC != null ? cabinTempC : JSONObject.NULL);
            obj.put("targetTempC", targetTempC);
            obj.put("ts", ts);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to serialize VehicleStatus", e);
        }
        return obj;
    }

    public static VehicleStatus fromJson(JSONObject json) {
        if (json == null) return null;
        int soc = json.optInt("soc", 0);
        int rangeKm = json.optInt("rangeKm", 0);
        boolean locked = json.optBoolean("locked", false);
        boolean acOn = json.optBoolean("acOn", false);
        Integer cabinTempC = json.isNull("cabinTempC") || !json.has("cabinTempC")
                ? null
                : json.optInt("cabinTempC");
        int targetTempC = json.optInt("targetTempC", 17);
        long ts = json.optLong("ts", 0L);
        return new VehicleStatus(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VehicleStatus that = (VehicleStatus) o;
        return soc == that.soc
                && rangeKm == that.rangeKm
                && locked == that.locked
                && acOn == that.acOn
                && targetTempC == that.targetTempC
                && ts == that.ts
                && Objects.equals(cabinTempC, that.cabinTempC);
    }

    @Override
    public int hashCode() {
        return Objects.hash(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts);
    }

    @Override
    public String toString() {
        return "VehicleStatus{" +
                "soc=" + soc +
                ", rangeKm=" + rangeKm +
                ", locked=" + locked +
                ", acOn=" + acOn +
                ", cabinTempC=" + cabinTempC +
                ", targetTempC=" + targetTempC +
                ", ts=" + ts +
                '}';
    }
}
