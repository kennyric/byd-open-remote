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

    public WatchCommand(String id, String action, int tempC, String vin, int level) {
        this.id = id;
        this.action = action;
        this.tempC = tempC;
        this.vin = vin != null ? vin.trim() : "";
        this.level = level;
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
                && Objects.equals(id, that.id)
                && Objects.equals(action, that.action)
                && Objects.equals(vin, that.vin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, action, tempC, vin, level);
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
