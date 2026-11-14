package com.bydwatch.companion.data;

import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleSummary;

import java.util.Collections;
import java.util.List;

/**
 * Storage interface for BYD user credentials and app configuration.
 */
public interface ConfigStore {

    String getUsername();
    void setUsername(String username);

    String getPassword();
    void setPassword(String password);

    String getPin();
    void setPin(String pin);

    String getVin();
    void setVin(String vin);

    String getRegion();
    void setRegion(String region);

    int getDefaultTempC();
    void setDefaultTempC(int tempC);

    default String getCountryCode() { return ""; }
    default void setCountryCode(String countryCode) {}

    default String getVehicleName() { return VehicleSummary.DEFAULT_MODEL_NAME; }
    default void setVehicleName(String vehicleName) {}

    default String getCustomVehicleName() { return ""; }
    default void setCustomVehicleName(String customVehicleName) {}

    default List<VehicleSummary> getVehicles() { return Collections.emptyList(); }
    default void setVehicles(List<VehicleSummary> vehicles) {}

    default String getDistanceUnit() { return UnitPreferences.DIST_KM; }
    default void setDistanceUnit(String unit) {}

    default String getTempUnit() { return UnitPreferences.TEMP_C; }
    default void setTempUnit(String unit) {}

    default String getPressureUnit() { return UnitPreferences.PRES_KPA; }
    default void setPressureUnit(String unit) {}

    default String getWatchTarget() { return "dev"; }
    default void setWatchTarget(String target) {}

    default boolean isAlertsEnabled() { return false; }
    default void setAlertsEnabled(boolean enabled) {}

    default boolean isDisclaimerAccepted() { return false; }
    default void setDisclaimerAccepted(boolean accepted) {}

    int DEFAULT_WIDGET_SYNC_MINUTES = 30;
    int MIN_WIDGET_SYNC_MINUTES = 1;
    int MAX_WIDGET_SYNC_MINUTES = 1440;

    default int getWidgetSyncIntervalMinutes() { return DEFAULT_WIDGET_SYNC_MINUTES; }
    default void setWidgetSyncIntervalMinutes(int minutes) {}

    default boolean isHideExtraControls() { return false; }
    default void setHideExtraControls(boolean hide) {}

    default String getWidgetStatusJson() { return null; }
    default void setWidgetStatusJson(String json) {}

    default long getWidgetLastSyncMs() { return 0L; }
    default void setWidgetLastSyncMs(long syncMs) {}

    boolean isConfigured();

    void clear();
}
