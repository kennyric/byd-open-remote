package com.bydwatch.companion.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable telemetry snapshot representing vehicle state sent to the watch and displayed in UI.
 */
public final class VehicleStatus {

    public static final int CHARGING_UNKNOWN = -1;
    public static final int CHARGING_NONE = 0;
    public static final int CHARGING_ACTIVE = 1;
    public static final int CHARGING_CONNECTED = 15;

    public final int soc;
    public final int rangeKm;
    public final boolean locked;
    public final boolean acOn;
    public final Integer cabinTempC;
    public final int targetTempC;
    public final long ts;

    // Additive telemetry & multi-car metadata
    public final String vin;
    public final String modelName;
    public final int chargingState;
    public final boolean vehicleOn;
    public final Integer chargeRemainingMinutes;
    public final Double chargePowerKw;
    public transient String diagSummary;
    public final int doorsOpenCount;
    public final int windowsOpenCount;
    public final boolean trunkOpen;
    public final Integer totalMileageKm;
    public final Integer outsideTempC;
    public final Integer fuelPercent;
    public final Integer fuelRangeKm;
    public final Integer tireLfKpa;
    public final Integer tireRfKpa;
    public final Integer tireLrKpa;
    public final Integer tireRrKpa;
    public final Boolean batteryHeatOn;
    public final int driverSeatLevel;
    public final boolean canWindows;
    public final boolean canBatteryHeat;
    public final boolean canSeatClimate;
    public final boolean manualAc;
    public final Integer targetTempGear;
    public final Integer fanLevel;
    public final Integer windMode;
    public final String distanceUnit;
    public final String tempUnit;
    public final String pressureUnit;
    public final Double latitude;
    public final Double longitude;
    public final Long gpsTs;
    public final double recentKwhPer100Km;
    public final List<VehicleSummary> vehicles;

    public VehicleStatus(
            int soc,
            int rangeKm,
            boolean locked,
            boolean acOn,
            Integer cabinTempC,
            int targetTempC,
            long ts) {
        this(new Builder(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts));
    }

    private VehicleStatus(Builder b) {
        this.soc = b.soc;
        this.rangeKm = b.rangeKm;
        this.locked = b.locked;
        this.acOn = b.acOn;
        this.cabinTempC = b.cabinTempC;
        this.targetTempC = b.targetTempC;
        this.ts = b.ts;
        this.vin = b.vin;
        this.modelName = b.modelName;
        this.chargingState = b.chargingState;
        this.vehicleOn = b.vehicleOn;
        this.chargeRemainingMinutes = b.chargeRemainingMinutes;
        this.chargePowerKw = b.chargePowerKw;
        this.diagSummary = b.diagSummary;
        this.doorsOpenCount = b.doorsOpenCount;
        this.windowsOpenCount = b.windowsOpenCount;
        this.trunkOpen = b.trunkOpen;
        this.totalMileageKm = b.totalMileageKm;
        this.outsideTempC = b.outsideTempC;
        this.fuelPercent = b.fuelPercent;
        this.fuelRangeKm = b.fuelRangeKm;
        this.tireLfKpa = b.tireLfKpa;
        this.tireRfKpa = b.tireRfKpa;
        this.tireLrKpa = b.tireLrKpa;
        this.tireRrKpa = b.tireRrKpa;
        this.batteryHeatOn = b.batteryHeatOn;
        this.driverSeatLevel = b.driverSeatLevel;
        this.canWindows = b.canWindows;
        this.canBatteryHeat = b.canBatteryHeat;
        this.canSeatClimate = b.canSeatClimate;
        this.manualAc = b.manualAc;
        this.targetTempGear = b.targetTempGear;
        this.fanLevel = b.fanLevel;
        this.windMode = b.windMode;
        this.distanceUnit = UnitPreferences.normalizeDistanceUnit(b.distanceUnit);
        this.tempUnit = UnitPreferences.normalizeTempUnit(b.tempUnit);
        this.pressureUnit = UnitPreferences.normalizePressureUnit(b.pressureUnit);
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.gpsTs = b.gpsTs;
        this.recentKwhPer100Km = b.recentKwhPer100Km;
        this.vehicles = b.vehicles != null
                ? Collections.unmodifiableList(new ArrayList<>(b.vehicles))
                : Collections.emptyList();
    }

    public boolean isCharging() {
        return chargingState == CHARGING_ACTIVE;
    }

    public boolean hasChargePowerKw() {
        return chargePowerKw != null
                && !chargePowerKw.isNaN()
                && !chargePowerKw.isInfinite()
                && chargePowerKw > 0.0;
    }

    public boolean hasTpms() {
        return tireLfKpa != null || tireRfKpa != null || tireLrKpa != null || tireRrKpa != null;
    }

    public boolean hasAnyOpening() {
        return doorsOpenCount > 0 || windowsOpenCount > 0 || trunkOpen;
    }

    public boolean hasLocation() {
        return isValidCoordinates(latitude, longitude);
    }

    public static boolean isValidCoordinates(Double lat, Double lng) {
        if (lat == null || lng == null) return false;
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 1e-6 && Math.abs(lng) < 1e-6);
    }

    public Builder toBuilder() {
        return new Builder(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts)
                .vin(vin)
                .modelName(modelName)
                .chargingState(chargingState)
                .vehicleOn(vehicleOn)
                .chargeRemainingMinutes(chargeRemainingMinutes)
                .chargePowerKw(chargePowerKw)
                .diagSummary(diagSummary)
                .doorsOpenCount(doorsOpenCount)
                .windowsOpenCount(windowsOpenCount)
                .trunkOpen(trunkOpen)
                .totalMileageKm(totalMileageKm)
                .outsideTempC(outsideTempC)
                .fuelPercent(fuelPercent)
                .fuelRangeKm(fuelRangeKm)
                .tirePressuresKpa(tireLfKpa, tireRfKpa, tireLrKpa, tireRrKpa)
                .batteryHeatOn(batteryHeatOn)
                .driverSeatLevel(driverSeatLevel)
                .capabilities(canWindows, canBatteryHeat, canSeatClimate)
                .manualAc(manualAc)
                .acPreset(targetTempGear, fanLevel, windMode)
                .units(distanceUnit, tempUnit, pressureUnit)
                .location(latitude, longitude, gpsTs)
                .recentKwhPer100Km(recentKwhPer100Km)
                .vehicles(vehicles);
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

            if (vin != null && !vin.isEmpty()) obj.put("vin", vin);
            if (modelName != null && !modelName.isEmpty()) obj.put("modelName", modelName);
            if (chargingState != CHARGING_UNKNOWN) obj.put("charging", chargingState);
            if (vehicleOn) obj.put("carOn", true);
            if (chargeRemainingMinutes != null && chargeRemainingMinutes >= 0) {
                obj.put("chgMin", chargeRemainingMinutes);
            }
            if (hasChargePowerKw()) {
                obj.put("chgKw", Math.round(chargePowerKw * 10.0) / 10.0);
            }
            if (doorsOpenCount > 0) obj.put("openDoors", doorsOpenCount);
            if (windowsOpenCount > 0) obj.put("openWins", windowsOpenCount);
            if (trunkOpen) obj.put("trunkOpen", true);
            if (totalMileageKm != null && totalMileageKm > 0) obj.put("odoKm", totalMileageKm);
            if (outsideTempC != null) obj.put("outTempC", outsideTempC);
            if (fuelPercent != null && fuelPercent >= 0) obj.put("fuelPct", fuelPercent);
            if (fuelRangeKm != null && fuelRangeKm >= 0) obj.put("fuelKm", fuelRangeKm);
            if (hasTpms()) {
                JSONArray tpms = new JSONArray();
                tpms.put(tireLfKpa != null ? tireLfKpa : JSONObject.NULL);
                tpms.put(tireRfKpa != null ? tireRfKpa : JSONObject.NULL);
                tpms.put(tireLrKpa != null ? tireLrKpa : JSONObject.NULL);
                tpms.put(tireRrKpa != null ? tireRrKpa : JSONObject.NULL);
                obj.put("tpms", tpms);
            }
            if (batteryHeatOn != null) obj.put("batHeat", batteryHeatOn);
            if (driverSeatLevel > 0) obj.put("seat", driverSeatLevel);
            obj.put("capWin", canWindows);
            obj.put("capBatHeat", canBatteryHeat);
            obj.put("capSeat", canSeatClimate);
            if (manualAc) obj.put("manAc", true);
            if (targetTempGear != null) obj.put("tempGear", targetTempGear);
            if (fanLevel != null) obj.put("fanLevel", fanLevel);
            if (windMode != null) obj.put("windMode", windMode);
            obj.put("uDist", distanceUnit);
            obj.put("uTemp", tempUnit);
            obj.put("uPres", pressureUnit);
            if (hasLocation()) {
                obj.put("lat", latitude);
                obj.put("lng", longitude);
                if (gpsTs != null && gpsTs > 0) {
                    obj.put("gpsTs", gpsTs);
                }
            }
            if (recentKwhPer100Km > 0d) obj.put("recentKwh", recentKwhPer100Km);
            if (!vehicles.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (VehicleSummary v : vehicles) {
                    if (v != null && !v.vin.isEmpty()) {
                        JSONObject item = new JSONObject();
                        item.put("vin", v.vin);
                        item.put("name", v.watchShortLabel());
                        arr.put(item);
                    }
                }
                obj.put("cars", arr);
            }
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

        Builder b = new Builder(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts);
        b.vin(json.optString("vin", ""));
        b.modelName(json.optString("modelName", ""));
        if (json.has("charging")) b.chargingState(json.optInt("charging", CHARGING_UNKNOWN));
        if (json.optBoolean("carOn", false)) b.vehicleOn(true);
        if (json.has("chgMin") && !json.isNull("chgMin")) b.chargeRemainingMinutes(json.optInt("chgMin"));
        if (json.has("chgKw") && !json.isNull("chgKw")) {
            b.chargePowerKw(json.optDouble("chgKw", Double.NaN));
        } else if (json.has("chargePowerKw") && !json.isNull("chargePowerKw")) {
            b.chargePowerKw(json.optDouble("chargePowerKw", Double.NaN));
        }
        b.doorsOpenCount(json.optInt("openDoors", 0));
        b.windowsOpenCount(json.optInt("openWins", 0));
        b.trunkOpen(json.optBoolean("trunkOpen", false));
        if (json.has("odoKm") && !json.isNull("odoKm")) b.totalMileageKm(json.optInt("odoKm"));
        if (json.has("outTempC") && !json.isNull("outTempC")) b.outsideTempC(json.optInt("outTempC"));
        if (json.has("fuelPct") && !json.isNull("fuelPct")) b.fuelPercent(json.optInt("fuelPct"));
        if (json.has("fuelKm") && !json.isNull("fuelKm")) b.fuelRangeKm(json.optInt("fuelKm"));

        JSONArray tpms = json.optJSONArray("tpms");
        if (tpms != null && tpms.length() >= 4) {
            Integer lf = tpms.isNull(0) ? null : tpms.optInt(0);
            Integer rf = tpms.isNull(1) ? null : tpms.optInt(1);
            Integer lr = tpms.isNull(2) ? null : tpms.optInt(2);
            Integer rr = tpms.isNull(3) ? null : tpms.optInt(3);
            b.tirePressuresKpa(lf, rf, lr, rr);
        }

        if (json.has("batHeat") && !json.isNull("batHeat")) b.batteryHeatOn(json.optBoolean("batHeat"));
        if (json.has("seat")) b.driverSeatLevel(json.optInt("seat", 0));
        b.capabilities(
                json.optBoolean("capWin", true),
                json.optBoolean("capBatHeat", false),
                json.optBoolean("capSeat", false)
        );
        if (json.optBoolean("manAc", false)) {
            b.manualAc(true);
        }
        Integer gear = (json.has("tempGear") && !json.isNull("tempGear")) ? json.optInt("tempGear") : null;
        Integer fan = (json.has("fanLevel") && !json.isNull("fanLevel")) ? json.optInt("fanLevel") : null;
        Integer vent = (json.has("windMode") && !json.isNull("windMode")) ? json.optInt("windMode") : null;
        if (json.has("windMode") && !json.isNull("windMode")) { /* handled below */ }
        if (json.has("recentKwh") && !json.isNull("recentKwh")) {
            b.recentKwhPer100Km(json.optDouble("recentKwh", -1d));
        }
        b.acPreset(gear, fan, vent);
        b.units(
                json.optString("uDist", UnitPreferences.DIST_KM),
                json.optString("uTemp", UnitPreferences.TEMP_C),
                json.optString("uPres", UnitPreferences.PRES_KPA)
        );
        if (json.has("lat") && json.has("lng") && !json.isNull("lat") && !json.isNull("lng")) {
            double lat = json.optDouble("lat", Double.NaN);
            double lng = json.optDouble("lng", Double.NaN);
            Long gTs = (json.has("gpsTs") && !json.isNull("gpsTs")) ? json.optLong("gpsTs", 0L) : null;
            b.location(lat, lng, gTs);
        }

        JSONArray carsArr = json.optJSONArray("cars");
        if (carsArr != null) {
            List<VehicleSummary> list = new ArrayList<>();
            for (int i = 0; i < carsArr.length(); i++) {
                VehicleSummary v = VehicleSummary.fromJson(carsArr.optJSONObject(i));
                if (v != null) list.add(v);
            }
            b.vehicles(list);
        }
        return b.build();
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
                && chargingState == that.chargingState
                && vehicleOn == that.vehicleOn
                && doorsOpenCount == that.doorsOpenCount
                && windowsOpenCount == that.windowsOpenCount
                && trunkOpen == that.trunkOpen
                && driverSeatLevel == that.driverSeatLevel
                && canWindows == that.canWindows
                && canBatteryHeat == that.canBatteryHeat
                && canSeatClimate == that.canSeatClimate
                && manualAc == that.manualAc
                && Objects.equals(targetTempGear, that.targetTempGear)
                && Objects.equals(fanLevel, that.fanLevel)
                && Objects.equals(windMode, that.windMode)
                && Objects.equals(cabinTempC, that.cabinTempC)
                && Objects.equals(vin, that.vin)
                && Objects.equals(modelName, that.modelName)
                && Objects.equals(chargeRemainingMinutes, that.chargeRemainingMinutes)
                && Objects.equals(chargePowerKw, that.chargePowerKw)
                && Objects.equals(totalMileageKm, that.totalMileageKm)
                && Objects.equals(outsideTempC, that.outsideTempC)
                && Objects.equals(fuelPercent, that.fuelPercent)
                && Objects.equals(fuelRangeKm, that.fuelRangeKm)
                && Objects.equals(tireLfKpa, that.tireLfKpa)
                && Objects.equals(tireRfKpa, that.tireRfKpa)
                && Objects.equals(tireLrKpa, that.tireLrKpa)
                && Objects.equals(tireRrKpa, that.tireRrKpa)
                && Objects.equals(batteryHeatOn, that.batteryHeatOn)
                && Objects.equals(distanceUnit, that.distanceUnit)
                && Objects.equals(tempUnit, that.tempUnit)
                && Objects.equals(pressureUnit, that.pressureUnit)
                && Objects.equals(latitude, that.latitude)
                && Objects.equals(longitude, that.longitude)
                && Objects.equals(gpsTs, that.gpsTs)
                && Double.compare(recentKwhPer100Km, that.recentKwhPer100Km) == 0
                && Objects.equals(vehicles, that.vehicles);
    }

    @Override
    public int hashCode() {
        return Objects.hash(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts,
                vin, modelName, chargingState, vehicleOn, chargeRemainingMinutes, chargePowerKw,
                doorsOpenCount, windowsOpenCount, trunkOpen, totalMileageKm,
                outsideTempC, fuelPercent, fuelRangeKm,
                tireLfKpa, tireRfKpa, tireLrKpa, tireRrKpa,
                batteryHeatOn, driverSeatLevel,
                canWindows, canBatteryHeat, canSeatClimate, manualAc,
                targetTempGear, fanLevel, windMode,
                distanceUnit, tempUnit, pressureUnit, latitude, longitude, gpsTs, recentKwhPer100Km, vehicles);
    }

    @Override
    public String toString() {
        return "VehicleStatus{" +
                "soc=" + soc +
                ", rangeKm=" + rangeKm +
                ", locked=" + locked +
                ", acOn=" + acOn +
                ", modelName='" + modelName + '\'' +
                ", ts=" + ts +
                '}';
    }

    public static final class Builder {
        private final int soc;
        private final int rangeKm;
        private final boolean locked;
        private boolean acOn;
        private Integer cabinTempC;
        private final int targetTempC;
        private long ts;

        private String vin = "";
        private String modelName = "";
        private int chargingState = CHARGING_UNKNOWN;
        private boolean vehicleOn;
        private Integer chargeRemainingMinutes;
        private Double chargePowerKw;
        private String diagSummary;
        private int doorsOpenCount;
        private int windowsOpenCount;
        private boolean trunkOpen;
        private Integer totalMileageKm;
        private Integer outsideTempC;
        private Integer fuelPercent;
        private Integer fuelRangeKm;
        private Integer tireLfKpa;
        private Integer tireRfKpa;
        private Integer tireLrKpa;
        private Integer tireRrKpa;
        private Boolean batteryHeatOn;
        private int driverSeatLevel;
        private boolean canWindows = true;
        private boolean canBatteryHeat;
        private boolean canSeatClimate;
        private boolean manualAc;
        private Integer targetTempGear;
        private Integer fanLevel;
        private Integer windMode;
        private String distanceUnit = UnitPreferences.DIST_KM;
        private String tempUnit = UnitPreferences.TEMP_C;
        private String pressureUnit = UnitPreferences.PRES_KPA;
        private Double latitude;
        private Double longitude;
        private Long gpsTs;
        private double recentKwhPer100Km = -1d;
        private List<VehicleSummary> vehicles;

        public Builder(int soc, int rangeKm, boolean locked, boolean acOn,
                       Integer cabinTempC, int targetTempC, long ts) {
            this.soc = soc;
            this.rangeKm = rangeKm;
            this.locked = locked;
            this.acOn = acOn;
            this.cabinTempC = cabinTempC;
            this.targetTempC = targetTempC;
            this.ts = ts;
        }

        public Builder vin(String vin) { this.vin = vin != null ? vin : ""; return this; }
        public Builder acOn(boolean acOn) { this.acOn = acOn; return this; }
        public Builder cabinTempC(Integer cabinTempC) { this.cabinTempC = cabinTempC; return this; }
        public Builder modelName(String modelName) { this.modelName = modelName != null ? modelName : ""; return this; }
        public Builder chargingState(int chargingState) { this.chargingState = chargingState; return this; }
        public Builder vehicleOn(boolean vehicleOn) { this.vehicleOn = vehicleOn; return this; }
        public Builder diagSummary(String diagSummary) { this.diagSummary = diagSummary; return this; }
        public Builder chargeRemainingMinutes(Integer mins) { this.chargeRemainingMinutes = mins; return this; }
        public Builder chargePowerKw(Double kw) {
            if (kw != null && !kw.isNaN() && !kw.isInfinite() && kw > 0.0) {
                this.chargePowerKw = kw;
            } else {
                this.chargePowerKw = null;
            }
            return this;
        }
        public Builder doorsOpenCount(int count) { this.doorsOpenCount = Math.max(0, count); return this; }
        public Builder windowsOpenCount(int count) { this.windowsOpenCount = Math.max(0, count); return this; }
        public Builder trunkOpen(boolean open) { this.trunkOpen = open; return this; }
        public Builder totalMileageKm(Integer km) { this.totalMileageKm = km; return this; }
        public Builder outsideTempC(Integer c) { this.outsideTempC = c; return this; }
        /** Epoch seconds of the reading; shown as the "last updated" clock. */
        public Builder ts(long epochSeconds) { this.ts = epochSeconds; return this; }
        public Builder fuelPercent(Integer pct) { this.fuelPercent = pct; return this; }
        public Builder fuelRangeKm(Integer km) { this.fuelRangeKm = km; return this; }
        public Builder tirePressuresKpa(Integer lf, Integer rf, Integer lr, Integer rr) {
            this.tireLfKpa = lf;
            this.tireRfKpa = rf;
            this.tireLrKpa = lr;
            this.tireRrKpa = rr;
            return this;
        }
        public Builder batteryHeatOn(Boolean on) { this.batteryHeatOn = on; return this; }
        public Builder driverSeatLevel(int level) { this.driverSeatLevel = Math.max(0, Math.min(2, level)); return this; }
        public Builder capabilities(boolean win, boolean batHeat, boolean seat) {
            this.canWindows = win;
            this.canBatteryHeat = batHeat;
            this.canSeatClimate = seat;
            return this;
        }
        public Builder manualAc(boolean manualAc) {
            this.manualAc = manualAc;
            return this;
        }
        public Builder acPreset(Integer tempGear, Integer fanLevel, Integer windMode) {
            this.targetTempGear = (tempGear != null && tempGear >= 1 && tempGear <= 9) ? tempGear : null;
            this.fanLevel = (fanLevel != null && fanLevel >= 1 && fanLevel <= 7) ? fanLevel : null;
            this.windMode = (windMode != null && windMode >= 1 && windMode <= 3) ? windMode : null;
            return this;
        }
        public Builder units(String dist, String temp, String pres) {
            this.distanceUnit = UnitPreferences.normalizeDistanceUnit(dist);
            this.tempUnit = UnitPreferences.normalizeTempUnit(temp);
            this.pressureUnit = UnitPreferences.normalizePressureUnit(pres);
            return this;
        }
        public Builder location(Double latitude, Double longitude, Long gpsTs) {
            if (isValidCoordinates(latitude, longitude)) {
                this.latitude = latitude;
                this.longitude = longitude;
                this.gpsTs = gpsTs;
            } else {
                this.latitude = null;
                this.longitude = null;
                this.gpsTs = null;
            }
            return this;
        }
        public Builder recentKwhPer100Km(double kwhPer100Km) {
            this.recentKwhPer100Km = Double.isFinite(kwhPer100Km) && kwhPer100Km > 0d ? kwhPer100Km : -1d;
            return this;
        }
        public Builder vehicles(List<VehicleSummary> vehicles) { this.vehicles = vehicles; return this; }
        public VehicleStatus build() { return new VehicleStatus(this); }
    }
}
