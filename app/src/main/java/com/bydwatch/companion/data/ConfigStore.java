package com.bydwatch.companion.data;

import com.bydwatch.companion.model.CustomActionCombo;
import com.bydwatch.companion.model.HomeButtonConfig;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.model.WidgetSlotConfig;

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

    int DEFAULT_TEMP_GEAR = 3;
    int MIN_TEMP_GEAR = 1;
    int MAX_TEMP_GEAR = 9;
    int DEFAULT_FAN_LEVEL = 4;
    int MIN_FAN_LEVEL = 1;
    int MAX_FAN_LEVEL = 7;
    int WIND_MODE_BODY = 1;
    int WIND_MODE_BODY_FEET = 2;
    int WIND_MODE_FEET = 3;
    int DEFAULT_WIND_MODE = WIND_MODE_BODY;

    default int getDefaultTempGear() { return DEFAULT_TEMP_GEAR; }
    default void setDefaultTempGear(int gear) {}

    default int getDefaultFanLevel() { return DEFAULT_FAN_LEVEL; }
    default void setDefaultFanLevel(int level) {}

    default int getDefaultWindMode() { return DEFAULT_WIND_MODE; }
    default void setDefaultWindMode(int windMode) {}

    default boolean isManualAcMode() { return false; }
    default void setManualAcMode(boolean manual) {}
    default Boolean getManualAcOverride() { return null; }
    default boolean isDetectedManualAc() { return false; }
    default void setDetectedManualAc(boolean detected) {}

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

    int WIDGET_STYLE_CAPSULE = 1;
    int WIDGET_STYLE_RING = 2;
    int WIDGET_STYLE_BENTO = 3;
    int WIDGET_STYLE_TANK = 4;
    int DEFAULT_WIDGET_STYLE = WIDGET_STYLE_CAPSULE;

    default int getWidgetSyncIntervalMinutes() { return DEFAULT_WIDGET_SYNC_MINUTES; }
    default void setWidgetSyncIntervalMinutes(int minutes) {}

    default int getWidgetStyle() { return DEFAULT_WIDGET_STYLE; }
    default void setWidgetStyle(int style) {}

    static int normalizeWidgetStyle(int style) {
        if (style >= WIDGET_STYLE_CAPSULE && style <= WIDGET_STYLE_TANK) {
            return style;
        }
        return DEFAULT_WIDGET_STYLE;
    }

    default boolean isHideExtraControls() { return false; }
    default void setHideExtraControls(boolean hide) {}

    default boolean isVerboseLogsEnabled() { return false; }
    default void setVerboseLogsEnabled(boolean enabled) {}

    String LANG_SYSTEM = "system";
    String LANG_EN = "en";
    String LANG_ID = "id";
    String DEFAULT_APP_LANGUAGE = LANG_SYSTEM;

    default String getAppLanguage() { return DEFAULT_APP_LANGUAGE; }
    default void setAppLanguage(String language) {}

    static String normalizeAppLanguage(String language) {
        if (language == null) {
            return DEFAULT_APP_LANGUAGE;
        }
        String clean = language.trim().toLowerCase(java.util.Locale.US);
        if (clean.equals("id") || clean.equals("in") || clean.startsWith("id-") || clean.startsWith("in-")
                || clean.equals("indonesian") || clean.equals("bahasa")) {
            return LANG_ID;
        }
        if (clean.equals("en") || clean.startsWith("en-")) {
            return LANG_EN;
        }
        return LANG_SYSTEM;
    }

    default String getWidgetStatusJson() { return null; }
    default void setWidgetStatusJson(String json) {}

    default long getWidgetLastSyncMs() { return 0L; }
    default void setWidgetLastSyncMs(long syncMs) {}

    default String getActionCombosJson() { return null; }
    default void setActionCombosJson(String json) {}

    default List<CustomActionCombo> getActionCombos() {
        return CustomActionCombo.listFromJson(getActionCombosJson());
    }

    default void setActionCombos(List<CustomActionCombo> combos) {
        setActionCombosJson(CustomActionCombo.listToJson(combos));
    }

    default String getWidgetSlotsJson() { return null; }
    default void setWidgetSlotsJson(String json) {}

    default List<WidgetSlotConfig> getWidgetSlots() {
        return WidgetSlotConfig.listFromJson(getWidgetSlotsJson());
    }

    default void setWidgetSlots(List<WidgetSlotConfig> slots) {
        setWidgetSlotsJson(WidgetSlotConfig.listToJson(slots));
    }

    default String getHomeCustomButtonsJson() { return null; }
    default void setHomeCustomButtonsJson(String json) {}

    default List<HomeButtonConfig> getHomeCustomButtons() {
        return HomeButtonConfig.listFromJson(getHomeCustomButtonsJson());
    }

    default void setHomeCustomButtons(List<HomeButtonConfig> buttons) {
        setHomeCustomButtonsJson(HomeButtonConfig.listToJson(buttons));
    }

    default boolean isShowHomeClimate() { return true; }
    default void setShowHomeClimate(boolean show) {}

    default boolean isShowHomeAlerts() { return true; }
    default void setShowHomeAlerts(boolean show) {}

    default boolean isShowHomeLocation() { return true; }
    default void setShowHomeLocation(boolean show) {}

    default boolean isShowHomeHealth() { return true; }
    default void setShowHomeHealth(boolean show) {}

    boolean isConfigured();

    void clear();
}
