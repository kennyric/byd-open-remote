package com.bydwatch.companion.protocol;

import java.util.Objects;

/**
 * Validated command received from the Huawei Watch GT 4 companion app.
 */
public final class WatchCommand {

    public final String id;
    public final String action;
    public final int tempC;
    public final String vin;
    public final int level;
    public final int tempGear;
    public final int fanLevel;
    public final int windMode;
    public final String acMode;

    public WatchCommand(String id, String action, int tempC, String vin, int level) {
        this(id, action, tempC, vin, level, -1, -1, -1, "");
    }

    public WatchCommand(String id, String action, int tempC, String vin, int level,
                        int tempGear, int fanLevel) {
        this(id, action, tempC, vin, level, tempGear, fanLevel, -1, "");
    }

    public WatchCommand(String id, String action, int tempC, String vin, int level,
                        int tempGear, int fanLevel, int windMode) {
        this(id, action, tempC, vin, level, tempGear, fanLevel, windMode, "");
    }

    public WatchCommand(String id, String action, int tempC, String vin, int level,
                        int tempGear, int fanLevel, int windMode, String acMode) {
        this.id = id;
        this.action = action;
        this.tempC = tempC;
        this.vin = vin != null ? vin.trim() : "";
        this.level = level;
        this.tempGear = tempGear;
        this.fanLevel = fanLevel;
        this.windMode = windMode;
        this.acMode = acMode != null ? acMode.trim() : "";
    }

    public WatchCommand(String id, String action, int tempC) {
        this(id, action, tempC, "", -1);
    }

    public WatchCommand(String id, String action) {
        this(id, action, 17, "", -1);
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public int getTempC() {
        return tempC;
    }

    public String getVin() {
        return vin;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WatchCommand that = (WatchCommand) o;
        return tempC == that.tempC
                && level == that.level
                && tempGear == that.tempGear
                && fanLevel == that.fanLevel
                && windMode == that.windMode
                && Objects.equals(id, that.id)
                && Objects.equals(action, that.action)
                && Objects.equals(vin, that.vin)
                && Objects.equals(acMode, that.acMode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, action, tempC, vin, level, tempGear, fanLevel, windMode, acMode);
    }

    @Override
    public String toString() {
        return "WatchCommand{" +
                "id='" + id + '\'' +
                ", action='" + action + '\'' +
                ", tempC=" + tempC +
                ", vin='" + vin + '\'' +
                ", level=" + level +
                '}';
    }
}
