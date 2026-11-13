package com.bydwatch.companion.protocol;

import java.util.Objects;

/**
 * Validated command received from the Huawei Watch GT 4 companion app.
 */
public final class WatchCommand {

    public final String id;
    public final String action;
    public final int tempC;

    public WatchCommand(String id, String action, int tempC) {
        this.id = id;
        this.action = action;
        this.tempC = tempC;
    }

    public WatchCommand(String id, String action) {
        this(id, action, 17);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WatchCommand that = (WatchCommand) o;
        return tempC == that.tempC && Objects.equals(id, that.id) && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, action, tempC);
    }

    @Override
    public String toString() {
        return "WatchCommand{" +
                "id='" + id + '\'' +
                ", action='" + action + '\'' +
                ", tempC=" + tempC +
                '}';
    }
}
