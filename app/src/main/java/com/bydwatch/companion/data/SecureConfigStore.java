package com.bydwatch.companion.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleSummary;

import java.util.List;

/**
 * Keystore-backed encrypted storage for BYD user credentials and app settings.
 */
public class SecureConfigStore implements ConfigStore {

    public static final String DEFAULT_REGION = "id";
    public static final int DEFAULT_TEMP_C = 17;

    private static final String PREFS_FILE = "byd_secure_prefs";
    private static final String PREF_USERNAME = "byd_username";
    private static final String PREF_PASSWORD = "byd_password";
    private static final String PREF_PIN = "byd_pin";
    private static final String PREF_VIN = "byd_vin";
    private static final String PREF_REGION = "byd_region";
    private static final String PREF_COUNTRY_CODE = "byd_country_code";
    private static final String PREF_VEHICLE_NAME = "byd_vehicle_name";
    private static final String PREF_CUSTOM_VEHICLE_NAME = "byd_custom_vehicle_name";
    private static final String PREF_VEHICLES_JSON = "byd_vehicles_json";
    private static final String PREF_DEFAULT_TEMP_C = "byd_default_temp_c";
    private static final String PREF_DEFAULT_TEMP_GEAR = "byd_default_temp_gear";
    private static final String PREF_DEFAULT_FAN_LEVEL = "byd_default_fan_level";
    private static final String PREF_DEFAULT_WIND_MODE = "byd_default_wind_mode";
    private static final String PREF_MANUAL_AC_OVERRIDE = "byd_manual_ac_override";
    private static final String PREF_MANUAL_AC_DETECTED = "byd_manual_ac_detected";
    private static final String PREF_DIST_UNIT = "byd_dist_unit";
    private static final String PREF_TEMP_UNIT = "byd_temp_unit";
    private static final String PREF_PRES_UNIT = "byd_pres_unit";
    private static final String PREF_WATCH_TARGET = "byd_watch_target";
    private static final String PREF_ALERTS_ENABLED = "byd_alerts_enabled";
    private static final String PREF_DISCLAIMER_ACCEPTED = "byd_disclaimer_accepted";
    private static final String PREF_WIDGET_SYNC_MINUTES = "byd_widget_sync_minutes";
    private static final String PREF_WIDGET_STYLE = "byd_widget_style";
    private static final String PREF_HIDE_EXTRA_CONTROLS = "byd_hide_extra_controls";
    private static final String PREF_VERBOSE_LOGS = "byd_verbose_logs";
    private static final String PREF_APP_LANGUAGE = "byd_app_language";
    private static final String PREF_WIDGET_STATUS_JSON = "byd_widget_status_json";
    private static final String PREF_WIDGET_LAST_SYNC_MS = "byd_widget_last_sync_ms";
    private static final String PREF_ACTION_COMBOS_JSON = "byd_action_combos_json";
    private static final String PREF_WIDGET_SLOTS_JSON = "byd_widget_slots_json";
    private static final String PREF_HOME_CUSTOM_BUTTONS_JSON = "byd_home_custom_buttons_json";
    private static final String PREF_SHOW_HOME_CLIMATE = "byd_show_home_climate";
    private static final String PREF_SHOW_HOME_ALERTS = "byd_show_home_alerts";
    private static final String PREF_SHOW_HOME_LOCATION = "byd_show_home_location";
    private static final String PREF_SHOW_HOME_HEALTH = "byd_show_home_health";

    private final SharedPreferences prefs;

    public SecureConfigStore(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            this.prefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize encrypted config store", e);
        }
    }

    public SecureConfigStore(SharedPreferences prefs) {
        if (prefs == null) {
            throw new IllegalArgumentException("SharedPreferences cannot be null");
        }
        this.prefs = prefs;
    }

    @Override
    public String getUsername() {
        return prefs.getString(PREF_USERNAME, null);
    }

    @Override
    public void setUsername(String username) {
        prefs.edit().putString(PREF_USERNAME, username).apply();
    }

    @Override
    public String getPassword() {
        return prefs.getString(PREF_PASSWORD, null);
    }

    @Override
    public void setPassword(String password) {
        prefs.edit().putString(PREF_PASSWORD, password).apply();
    }

    @Override
    public String getPin() {
        return prefs.getString(PREF_PIN, null);
    }

    @Override
    public void setPin(String pin) {
        prefs.edit().putString(PREF_PIN, pin).apply();
    }

    @Override
    public String getVin() {
        return prefs.getString(PREF_VIN, null);
    }

    @Override
    public void setVin(String vin) {
        String prev = getVin();
        String next = vin != null ? vin.trim() : null;
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_VIN, next);
        if (prev != null && !prev.trim().isEmpty() && next != null && !next.isEmpty()
                && !prev.trim().equals(next)) {
            editor.putBoolean(PREF_MANUAL_AC_DETECTED, false);
        }
        editor.apply();
    }

    @Override
    public String getRegion() {
        String region = prefs.getString(PREF_REGION, DEFAULT_REGION);
        return (region != null && !region.trim().isEmpty()) ? region : DEFAULT_REGION;
    }

    @Override
    public void setRegion(String region) {
        String toSave = (region != null && !region.trim().isEmpty()) ? region : DEFAULT_REGION;
        prefs.edit().putString(PREF_REGION, toSave).apply();
    }

    @Override
    public int getDefaultTempC() {
        return prefs.getInt(PREF_DEFAULT_TEMP_C, DEFAULT_TEMP_C);
    }

    @Override
    public void setDefaultTempC(int tempC) {
        int clamped = tempC;
        if (clamped < 17) clamped = 17;
        else if (clamped > 31) clamped = 31;
        prefs.edit().putInt(PREF_DEFAULT_TEMP_C, clamped).apply();
    }

    @Override
    public int getDefaultTempGear() {
        int raw = prefs.getInt(PREF_DEFAULT_TEMP_GEAR, DEFAULT_TEMP_GEAR);
        return Math.max(MIN_TEMP_GEAR, Math.min(MAX_TEMP_GEAR, raw));
    }

    @Override
    public void setDefaultTempGear(int gear) {
        int clamped = Math.max(MIN_TEMP_GEAR, Math.min(MAX_TEMP_GEAR, gear));
        prefs.edit().putInt(PREF_DEFAULT_TEMP_GEAR, clamped).apply();
    }

    @Override
    public int getDefaultFanLevel() {
        int raw = prefs.getInt(PREF_DEFAULT_FAN_LEVEL, DEFAULT_FAN_LEVEL);
        return Math.max(MIN_FAN_LEVEL, Math.min(MAX_FAN_LEVEL, raw));
    }

    @Override
    public void setDefaultFanLevel(int level) {
        int clamped = Math.max(MIN_FAN_LEVEL, Math.min(MAX_FAN_LEVEL, level));
        prefs.edit().putInt(PREF_DEFAULT_FAN_LEVEL, clamped).apply();
    }

    @Override
    public int getDefaultWindMode() {
        int raw = prefs.getInt(PREF_DEFAULT_WIND_MODE, DEFAULT_WIND_MODE);
        if (raw == WIND_MODE_BODY || raw == WIND_MODE_BODY_FEET || raw == WIND_MODE_FEET) {
            return raw;
        }
        return DEFAULT_WIND_MODE;
    }

    @Override
    public void setDefaultWindMode(int windMode) {
        int clamped = (windMode == WIND_MODE_BODY || windMode == WIND_MODE_BODY_FEET || windMode == WIND_MODE_FEET)
                ? windMode
                : DEFAULT_WIND_MODE;
        prefs.edit().putInt(PREF_DEFAULT_WIND_MODE, clamped).apply();
    }

    @Override
    public boolean isManualAcMode() {
        Boolean override = getManualAcOverride();
        return override != null ? override.booleanValue() : isDetectedManualAc();
    }

    @Override
    public void setManualAcMode(boolean manual) {
        prefs.edit().putInt(PREF_MANUAL_AC_OVERRIDE, manual ? 1 : 0).apply();
    }

    @Override
    public Boolean getManualAcOverride() {
        int state = prefs.getInt(PREF_MANUAL_AC_OVERRIDE, -1);
        if (state == 1) return Boolean.TRUE;
        if (state == 0) return Boolean.FALSE;
        return null;
    }

    @Override
    public boolean isDetectedManualAc() {
        return prefs.getBoolean(PREF_MANUAL_AC_DETECTED, false);
    }

    @Override
    public void setDetectedManualAc(boolean detected) {
        prefs.edit().putBoolean(PREF_MANUAL_AC_DETECTED, detected).apply();
    }

    @Override
    public String getCountryCode() {
        String code = prefs.getString(PREF_COUNTRY_CODE, "");
        return code != null ? code.trim().toUpperCase() : "";
    }

    @Override
    public void setCountryCode(String countryCode) {
        String val = countryCode != null ? countryCode.trim().toUpperCase() : "";
        prefs.edit().putString(PREF_COUNTRY_CODE, val).apply();
    }

    @Override
    public String getVehicleName() {
        String name = prefs.getString(PREF_VEHICLE_NAME, VehicleSummary.DEFAULT_MODEL_NAME);
        return (name != null && !name.trim().isEmpty()) ? name.trim() : VehicleSummary.DEFAULT_MODEL_NAME;
    }

    @Override
    public void setVehicleName(String vehicleName) {
        String val = VehicleSummary.normalizeModelName(vehicleName);
        prefs.edit().putString(PREF_VEHICLE_NAME, val).apply();
    }

    @Override
    public String getCustomVehicleName() {
        String custom = prefs.getString(PREF_CUSTOM_VEHICLE_NAME, "");
        return custom != null ? custom.trim() : "";
    }

    @Override
    public void setCustomVehicleName(String customVehicleName) {
        String val = customVehicleName != null ? customVehicleName.trim().replaceAll("\\s+", " ") : "";
        prefs.edit().putString(PREF_CUSTOM_VEHICLE_NAME, val).apply();
    }

    @Override
    public List<VehicleSummary> getVehicles() {
        return VehicleSummary.fromJsonArrayString(prefs.getString(PREF_VEHICLES_JSON, ""));
    }

    @Override
    public void setVehicles(List<VehicleSummary> vehicles) {
        prefs.edit().putString(PREF_VEHICLES_JSON, VehicleSummary.toJsonArrayString(vehicles)).apply();
    }

    @Override
    public String getDistanceUnit() {
        return UnitPreferences.normalizeDistanceUnit(prefs.getString(PREF_DIST_UNIT, UnitPreferences.DIST_KM));
    }

    @Override
    public void setDistanceUnit(String unit) {
        prefs.edit().putString(PREF_DIST_UNIT, UnitPreferences.normalizeDistanceUnit(unit)).apply();
    }

    @Override
    public String getTempUnit() {
        return UnitPreferences.normalizeTempUnit(prefs.getString(PREF_TEMP_UNIT, UnitPreferences.TEMP_C));
    }

    @Override
    public void setTempUnit(String unit) {
        prefs.edit().putString(PREF_TEMP_UNIT, UnitPreferences.normalizeTempUnit(unit)).apply();
    }

    @Override
    public String getPressureUnit() {
        return UnitPreferences.normalizePressureUnit(prefs.getString(PREF_PRES_UNIT, UnitPreferences.PRES_KPA));
    }

    @Override
    public void setPressureUnit(String unit) {
        prefs.edit().putString(PREF_PRES_UNIT, UnitPreferences.normalizePressureUnit(unit)).apply();
    }

    @Override
    public String getWatchTarget() {
        String target = prefs.getString(PREF_WATCH_TARGET, "dev");
        return "store".equalsIgnoreCase(target) ? "store" : "dev";
    }

    @Override
    public void setWatchTarget(String target) {
        prefs.edit().putString(PREF_WATCH_TARGET, "store".equalsIgnoreCase(target) ? "store" : "dev").apply();
    }

    @Override
    public boolean isAlertsEnabled() {
        return prefs.getBoolean(PREF_ALERTS_ENABLED, false);
    }

    @Override
    public void setAlertsEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_ALERTS_ENABLED, enabled).apply();
    }

    @Override
    public boolean isDisclaimerAccepted() {
        return prefs.getBoolean(PREF_DISCLAIMER_ACCEPTED, false);
    }

    @Override
    public void setDisclaimerAccepted(boolean accepted) {
        prefs.edit().putBoolean(PREF_DISCLAIMER_ACCEPTED, accepted).apply();
    }

    @Override
    public int getWidgetSyncIntervalMinutes() {
        int raw = prefs.getInt(PREF_WIDGET_SYNC_MINUTES, DEFAULT_WIDGET_SYNC_MINUTES);
        return Math.max(MIN_WIDGET_SYNC_MINUTES, Math.min(MAX_WIDGET_SYNC_MINUTES, raw));
    }

    @Override
    public void setWidgetSyncIntervalMinutes(int minutes) {
        int clamped = Math.max(MIN_WIDGET_SYNC_MINUTES, Math.min(MAX_WIDGET_SYNC_MINUTES, minutes));
        prefs.edit().putInt(PREF_WIDGET_SYNC_MINUTES, clamped).apply();
    }

    @Override
    public int getWidgetStyle() {
        return ConfigStore.normalizeWidgetStyle(
                prefs.getInt(PREF_WIDGET_STYLE, DEFAULT_WIDGET_STYLE));
    }

    @Override
    public void setWidgetStyle(int style) {
        prefs.edit().putInt(PREF_WIDGET_STYLE, ConfigStore.normalizeWidgetStyle(style)).apply();
    }

    @Override
    public boolean isHideExtraControls() {
        return prefs.getBoolean(PREF_HIDE_EXTRA_CONTROLS, false);
    }

    @Override
    public void setHideExtraControls(boolean hide) {
        prefs.edit().putBoolean(PREF_HIDE_EXTRA_CONTROLS, hide).apply();
    }

    @Override
    public boolean isVerboseLogsEnabled() {
        return prefs.getBoolean(PREF_VERBOSE_LOGS, false);
    }

    @Override
    public void setVerboseLogsEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_VERBOSE_LOGS, enabled).apply();
    }

    @Override
    public String getAppLanguage() {
        return ConfigStore.normalizeAppLanguage(
                prefs.getString(PREF_APP_LANGUAGE, ConfigStore.DEFAULT_APP_LANGUAGE));
    }

    @Override
    public void setAppLanguage(String language) {
        prefs.edit().putString(PREF_APP_LANGUAGE, ConfigStore.normalizeAppLanguage(language)).apply();
    }

    @Override
    public String getWidgetStatusJson() {
        return prefs.getString(PREF_WIDGET_STATUS_JSON, null);
    }

    @Override
    public void setWidgetStatusJson(String json) {
        if (json == null || json.isEmpty()) {
            prefs.edit().remove(PREF_WIDGET_STATUS_JSON).apply();
        } else {
            prefs.edit().putString(PREF_WIDGET_STATUS_JSON, json).apply();
        }
    }

    @Override
    public long getWidgetLastSyncMs() {
        return prefs.getLong(PREF_WIDGET_LAST_SYNC_MS, 0L);
    }

    @Override
    public void setWidgetLastSyncMs(long syncMs) {
        prefs.edit().putLong(PREF_WIDGET_LAST_SYNC_MS, Math.max(0L, syncMs)).apply();
    }

    @Override
    public String getActionCombosJson() {
        return prefs.getString(PREF_ACTION_COMBOS_JSON, null);
    }

    @Override
    public void setActionCombosJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            prefs.edit().remove(PREF_ACTION_COMBOS_JSON).apply();
        } else {
            prefs.edit().putString(PREF_ACTION_COMBOS_JSON, json).apply();
        }
    }

    @Override
    public String getWidgetSlotsJson() {
        return prefs.getString(PREF_WIDGET_SLOTS_JSON, null);
    }

    @Override
    public void setWidgetSlotsJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            prefs.edit().remove(PREF_WIDGET_SLOTS_JSON).apply();
        } else {
            prefs.edit().putString(PREF_WIDGET_SLOTS_JSON, json).apply();
        }
    }

    @Override
    public String getHomeCustomButtonsJson() {
        return prefs.getString(PREF_HOME_CUSTOM_BUTTONS_JSON, null);
    }

    @Override
    public void setHomeCustomButtonsJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            prefs.edit().remove(PREF_HOME_CUSTOM_BUTTONS_JSON).apply();
        } else {
            prefs.edit().putString(PREF_HOME_CUSTOM_BUTTONS_JSON, json).apply();
        }
    }

    @Override
    public boolean isShowHomeClimate() {
        return prefs.getBoolean(PREF_SHOW_HOME_CLIMATE, true);
    }

    @Override
    public void setShowHomeClimate(boolean show) {
        prefs.edit().putBoolean(PREF_SHOW_HOME_CLIMATE, show).apply();
    }

    @Override
    public boolean isShowHomeAlerts() {
        return prefs.getBoolean(PREF_SHOW_HOME_ALERTS, true);
    }

    @Override
    public void setShowHomeAlerts(boolean show) {
        prefs.edit().putBoolean(PREF_SHOW_HOME_ALERTS, show).apply();
    }

    @Override
    public boolean isShowHomeLocation() {
        return prefs.getBoolean(PREF_SHOW_HOME_LOCATION, true);
    }

    @Override
    public void setShowHomeLocation(boolean show) {
        prefs.edit().putBoolean(PREF_SHOW_HOME_LOCATION, show).apply();
    }

    @Override
    public boolean isShowHomeHealth() {
        return prefs.getBoolean(PREF_SHOW_HOME_HEALTH, true);
    }

    @Override
    public void setShowHomeHealth(boolean show) {
        prefs.edit().putBoolean(PREF_SHOW_HOME_HEALTH, show).apply();
    }

    @Override
    public boolean isConfigured() {
        String u = getUsername();
        String p = getPassword();
        String pin = getPin();
        String v = getVin();
        return u != null && !u.trim().isEmpty()
                && p != null && !p.trim().isEmpty()
                && pin != null && !pin.trim().isEmpty()
                && v != null && !v.trim().isEmpty();
    }

    @Override
    public void clear() {
        prefs.edit().clear().apply();
    }
}
