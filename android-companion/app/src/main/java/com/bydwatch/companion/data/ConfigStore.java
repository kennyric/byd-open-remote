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
    int DEFAULT_AC_DURATION_MINUTES = 20;
    int[] AC_DURATION_OPTIONS = {10, 15, 20, 25, 30};

    default int getDefaultTempGear() { return DEFAULT_TEMP_GEAR; }
    default void setDefaultTempGear(int gear) {}

    default int getDefaultFanLevel() { return DEFAULT_FAN_LEVEL; }
    default void setDefaultFanLevel(int level) {}

    default int getDefaultWindMode() { return DEFAULT_WIND_MODE; }
    default void setDefaultWindMode(int windMode) {}

    default int getDefaultAcDurationMinutes() { return DEFAULT_AC_DURATION_MINUTES; }
    default void setDefaultAcDurationMinutes(int minutes) {}

    static int normalizeAcDurationMinutes(int minutes) {
        for (int opt : AC_DURATION_OPTIONS) {
            if (opt == minutes) {
                return opt;
            }
        }
        return DEFAULT_AC_DURATION_MINUTES;
    }

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
    /** Per-vehicle icon override: either an emoji or a saved photo path (empty = default). */
    default String getVehicleIconEmoji(String vin) { return ""; }
    default void setVehicleIconEmoji(String vin, String emoji) {}
    default String getVehicleIconPhoto(String vin) { return ""; }
    default void setVehicleIconPhoto(String vin, String path) {}

    /** True once the user has swiped the Controls carousel at least once. */
    default boolean isVehicleSwipeHintDismissed() { return false; }
    default void setVehicleSwipeHintDismissed(boolean dismissed) {}

    /** Vehicle a widget instance is pinned to; empty means "follow the active car". */
    default String getWidgetVehicle(int appWidgetId) { return ""; }
    default void setWidgetVehicle(int appWidgetId, String vin) {}

    /** Per-vehicle display-name override, keyed by VIN (empty means auto-detected). */
    default String getVehicleCustomName(String vin) { return ""; }
    default void setVehicleCustomName(String vin, String customName) {}

    /**
     * Name to show for a car: its own override first, then the legacy global override written by
     * older versions, then empty so the caller can fall back to the detected model.
     */
    default String resolveVehicleCustomName(String vin) {
        String perVin = getVehicleCustomName(vin);
        if (perVin != null && !perVin.trim().isEmpty()) return perVin.trim();
        String legacy = getCustomVehicleName();
        return legacy != null ? legacy.trim() : "";
    }

    /** Package that receives the vehicle-status nudge; the patched Maps, renamed or not. */
    default String getVehicleStatusTargetPackage() { return VehicleStatusBroadcaster.DEFAULT_TARGET_PACKAGE; }
    default void setVehicleStatusTargetPackage(String targetPackage) {}

    /** Optional usable battery capacity in kWh (<= 0 means unset; falls back to car's estimated range). */
    default float getBatteryCapacityKwh() { return 0f; }
    default void setBatteryCapacityKwh(float kwh) {}

    default List<VehicleSummary> getVehicles() { return Collections.emptyList(); }
    default void setVehicles(List<VehicleSummary> vehicles) {}
    /** Epoch millis of the last successful vehicle-list fetch (0 when never fetched). */
    default long getVehiclesFetchedAt() { return 0L; }
    default void setVehiclesFetchedAt(long epochMillis) {}

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

    default long getLastUpdateCheckMs() { return 0L; }
    default void setLastUpdateCheckMs(long checkMs) {}

    default boolean isSupportCardDismissed() { return false; }
    default void setSupportCardDismissed(boolean dismissed) {}

    default boolean isSingleFlightActionEnabled() { return true; }
    default void setSingleFlightActionEnabled(boolean enabled) {}

    String BIOMETRIC_SCOPE_OFF = "off";
    String BIOMETRIC_SCOPE_APP = "app";
    String BIOMETRIC_SCOPE_WIDGET = "widget";
    String BIOMETRIC_SCOPE_BOTH = "both";
    String DEFAULT_BIOMETRIC_SCOPE = BIOMETRIC_SCOPE_OFF;
    int DEFAULT_BIOMETRIC_GRACE_MINUTES = 0;
    int MAX_BIOMETRIC_GRACE_MINUTES = 1440;

    static String normalizeBiometricScope(String scope) {
        if (scope == null) {
            return DEFAULT_BIOMETRIC_SCOPE;
        }
        String clean = scope.trim().toLowerCase(java.util.Locale.US);
        switch (clean) {
            case BIOMETRIC_SCOPE_APP:
            case BIOMETRIC_SCOPE_WIDGET:
            case BIOMETRIC_SCOPE_BOTH:
                return clean;
            default:
                return BIOMETRIC_SCOPE_OFF;
        }
    }

    static int normalizeBiometricGraceMinutes(int minutes) {
        return Math.max(0, Math.min(MAX_BIOMETRIC_GRACE_MINUTES, minutes));
    }

    default String getBiometricScope() { return DEFAULT_BIOMETRIC_SCOPE; }
    default void setBiometricScope(String scope) {}

    default int getBiometricGraceMinutes() { return DEFAULT_BIOMETRIC_GRACE_MINUTES; }
    default void setBiometricGraceMinutes(int minutes) {}

    default boolean isBiometricWidgetSyncRequired() { return false; }
    default void setBiometricWidgetSyncRequired(boolean required) {}

    default long getLastBiometricAuthElapsedMs() { return -1L; }
    default void setLastBiometricAuthElapsedMs(long elapsedMs) {}

    default int getLastBiometricAuthBootCount() { return -1; }
    default void setLastBiometricAuthBootCount(int bootCount) {}

    default void clearBiometricAuthTimestamp() {
        setLastBiometricAuthElapsedMs(-1L);
        setLastBiometricAuthBootCount(-1);
    }

    boolean isConfigured();

    void clear();
}
