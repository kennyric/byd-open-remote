package com.bydwatch.companion.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleSummary;

import org.json.JSONException;
import org.json.JSONObject;

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
    private static final String PREF_VEHICLES_FETCHED_AT = "byd_vehicles_fetched_at";
    private static final String PREF_VEHICLE_CUSTOM_NAMES = "byd_vehicle_custom_names";
    private static final String PREF_WIDGET_VEHICLES = "byd_widget_vehicles";
    private static final String PREF_VEHICLE_ICONS_EMOJI = "byd_vehicle_icons_emoji";
    private static final String PREF_VEHICLE_ICONS_PHOTO = "byd_vehicle_icons_photo";
    private static final String PREF_VEHICLE_SWIPE_HINT = "byd_vehicle_swipe_hint_dismissed";
    private static final String PREF_DEFAULT_TEMP_C = "byd_default_temp_c";
    private static final String PREF_DEFAULT_TEMP_GEAR = "byd_default_temp_gear";
    private static final String PREF_DEFAULT_FAN_LEVEL = "byd_default_fan_level";
    private static final String PREF_DEFAULT_WIND_MODE = "byd_default_wind_mode";
    private static final String PREF_DEFAULT_AC_DURATION_MINUTES = "byd_default_ac_duration_min";
    private static final String PREF_MANUAL_AC_OVERRIDE = "byd_manual_ac_override";
    private static final String PREF_MANUAL_AC_DETECTED = "byd_manual_ac_detected";
    private static final String PREF_DIST_UNIT = "byd_dist_unit";
    private static final String PREF_TEMP_UNIT = "byd_temp_unit";
    private static final String PREF_PRES_UNIT = "byd_pres_unit";
    private static final String PREF_WATCH_TARGET = "byd_watch_target";
    private static final String PREF_ALERTS_ENABLED = "byd_alerts_enabled";
    private static final String PREF_DISCLAIMER_ACCEPTED = "byd_disclaimer_accepted";
    private static final String PREF_WIDGET_SYNC_MINUTES = "byd_widget_sync_minutes";
    private static final String PREF_BATTERY_CAPACITY_KWH = "byd_battery_capacity_kwh";
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
    private static final String PREF_LAST_UPDATE_CHECK_MS = "byd_last_update_check_ms";
    private static final String PREF_SUPPORT_CARD_DISMISSED = "byd_support_card_dismissed";
    private static final String PREF_SINGLE_FLIGHT_ACTION = "byd_single_flight_action";
    private static final String PREF_BIOMETRIC_SCOPE = "byd_biometric_scope";
    private static final String PREF_BIOMETRIC_GRACE_MINUTES = "byd_biometric_grace_minutes";
    private static final String PREF_BIOMETRIC_WIDGET_SYNC = "byd_biometric_widget_sync";
    private static final String PREF_BIOMETRIC_LAST_AUTH_ELAPSED_MS = "byd_biometric_last_auth_elapsed_ms";
    private static final String PREF_BIOMETRIC_LAST_AUTH_BOOT_COUNT = "byd_biometric_last_auth_boot_count";

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
    public float getBatteryCapacityKwh() {
        float val = prefs.getFloat(PREF_BATTERY_CAPACITY_KWH, 0f);
        return val > 0f && Float.isFinite(val) ? val : 0f;
    }

    @Override
    public void setBatteryCapacityKwh(float kwh) {
        if (kwh > 0f && Float.isFinite(kwh)) {
            prefs.edit().putFloat(PREF_BATTERY_CAPACITY_KWH, kwh).apply();
        } else {
            prefs.edit().remove(PREF_BATTERY_CAPACITY_KWH).apply();
        }
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
    public int getDefaultAcDurationMinutes() {
        return ConfigStore.normalizeAcDurationMinutes(
                prefs.getInt(PREF_DEFAULT_AC_DURATION_MINUTES, DEFAULT_AC_DURATION_MINUTES));
    }

    @Override
    public void setDefaultAcDurationMinutes(int minutes) {
        prefs.edit().putInt(PREF_DEFAULT_AC_DURATION_MINUTES,
                ConfigStore.normalizeAcDurationMinutes(minutes)).apply();
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
    public String getVehicleCustomName(String vin) {
        if (vin == null || vin.trim().isEmpty()) return "";
        String name = readVehicleCustomNames().optString(vin.trim(), "");
        return name != null ? name.trim() : "";
    }

    @Override
    public void setVehicleCustomName(String vin, String customName) {
        if (vin == null || vin.trim().isEmpty()) return;
        String key = vin.trim();
        String val = customName != null ? customName.trim().replaceAll("\\s+", " ") : "";
        JSONObject names = readVehicleCustomNames();
        try {
            if (val.isEmpty()) {
                names.remove(key);
            } else {
                names.put(key, val);
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(PREF_VEHICLE_CUSTOM_NAMES, names.toString()).apply();
    }

    @Override
    public String getWidgetVehicle(int appWidgetId) {
        return readWidgetVehicles().optString(String.valueOf(appWidgetId), "");
    }

    @Override
    public void setWidgetVehicle(int appWidgetId, String vin) {
        JSONObject map = readWidgetVehicles();
        try {
            if (vin == null || vin.trim().isEmpty()) {
                map.remove(String.valueOf(appWidgetId));
            } else {
                map.put(String.valueOf(appWidgetId), vin.trim());
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(PREF_WIDGET_VEHICLES, map.toString()).apply();
    }

    @Override
    public String getVehicleIconEmoji(String vin) {
        return readVinMap(PREF_VEHICLE_ICONS_EMOJI).optString(keyFor(vin), "");
    }

    @Override
    public void setVehicleIconEmoji(String vin, String emoji) {
        writeVinMap(PREF_VEHICLE_ICONS_EMOJI, vin, emoji);
    }

    @Override
    public String getVehicleIconPhoto(String vin) {
        return readVinMap(PREF_VEHICLE_ICONS_PHOTO).optString(keyFor(vin), "");
    }

    @Override
    public void setVehicleIconPhoto(String vin, String path) {
        writeVinMap(PREF_VEHICLE_ICONS_PHOTO, vin, path);
    }

    @Override
    public boolean isVehicleSwipeHintDismissed() {
        return prefs.getBoolean(PREF_VEHICLE_SWIPE_HINT, false);
    }

    @Override
    public void setVehicleSwipeHintDismissed(boolean dismissed) {
        prefs.edit().putBoolean(PREF_VEHICLE_SWIPE_HINT, dismissed).apply();
    }

    private static String keyFor(String vin) {
        return vin != null ? vin.trim() : "";
    }

    private JSONObject readVinMap(String prefKey) {
        String raw = prefs.getString(prefKey, "{}");
        try {
            return new JSONObject(raw != null ? raw : "{}");
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void writeVinMap(String prefKey, String vin, String value) {
        String key = keyFor(vin);
        if (key.isEmpty()) return;
        JSONObject map = readVinMap(prefKey);
        try {
            if (value == null || value.trim().isEmpty()) map.remove(key);
            else map.put(key, value.trim());
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString(prefKey, map.toString()).apply();
    }

    private JSONObject readWidgetVehicles() {
        String raw = prefs.getString(PREF_WIDGET_VEHICLES, "{}");
        try {
            return new JSONObject(raw != null ? raw : "{}");
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private JSONObject readVehicleCustomNames() {
        String raw = prefs.getString(PREF_VEHICLE_CUSTOM_NAMES, "{}");
        try {
            return new JSONObject(raw != null ? raw : "{}");
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    @Override
    public List<VehicleSummary> getVehicles() {
        return VehicleSummary.fromJsonArrayString(prefs.getString(PREF_VEHICLES_JSON, ""));
    }

    @Override
    public void setVehicles(List<VehicleSummary> vehicles) {
        prefs.edit()
                .putString(PREF_VEHICLES_JSON, VehicleSummary.toJsonArrayString(vehicles))
                .putLong(PREF_VEHICLES_FETCHED_AT, System.currentTimeMillis())
                .apply();
    }

    @Override
    public long getVehiclesFetchedAt() {
        return prefs.getLong(PREF_VEHICLES_FETCHED_AT, 0L);
    }

    @Override
    public void setVehiclesFetchedAt(long epochMillis) {
        prefs.edit().putLong(PREF_VEHICLES_FETCHED_AT, epochMillis).apply();
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
    public long getLastUpdateCheckMs() {
        return prefs.getLong(PREF_LAST_UPDATE_CHECK_MS, 0L);
    }

    @Override
    public void setLastUpdateCheckMs(long checkMs) {
        prefs.edit().putLong(PREF_LAST_UPDATE_CHECK_MS, Math.max(0L, checkMs)).apply();
    }

    @Override
    public boolean isSupportCardDismissed() {
        return prefs.getBoolean(PREF_SUPPORT_CARD_DISMISSED, false);
    }

    @Override
    public void setSupportCardDismissed(boolean dismissed) {
        prefs.edit().putBoolean(PREF_SUPPORT_CARD_DISMISSED, dismissed).apply();
    }

    @Override
    public boolean isSingleFlightActionEnabled() {
        return prefs.getBoolean(PREF_SINGLE_FLIGHT_ACTION, true);
    }

    @Override
    public void setSingleFlightActionEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_SINGLE_FLIGHT_ACTION, enabled).apply();
    }

    @Override
    public String getBiometricScope() {
        return ConfigStore.normalizeBiometricScope(
                prefs.getString(PREF_BIOMETRIC_SCOPE, DEFAULT_BIOMETRIC_SCOPE)
        );
    }

    @Override
    public void setBiometricScope(String scope) {
        prefs.edit().putString(PREF_BIOMETRIC_SCOPE, ConfigStore.normalizeBiometricScope(scope)).apply();
    }

    @Override
    public int getBiometricGraceMinutes() {
        return ConfigStore.normalizeBiometricGraceMinutes(
                prefs.getInt(PREF_BIOMETRIC_GRACE_MINUTES, DEFAULT_BIOMETRIC_GRACE_MINUTES)
        );
    }

    @Override
    public void setBiometricGraceMinutes(int minutes) {
        prefs.edit().putInt(
                PREF_BIOMETRIC_GRACE_MINUTES,
                ConfigStore.normalizeBiometricGraceMinutes(minutes)
        ).apply();
    }

    @Override
    public boolean isBiometricWidgetSyncRequired() {
        return prefs.getBoolean(PREF_BIOMETRIC_WIDGET_SYNC, false);
    }

    @Override
    public void setBiometricWidgetSyncRequired(boolean required) {
        prefs.edit().putBoolean(PREF_BIOMETRIC_WIDGET_SYNC, required).apply();
    }

    @Override
    public long getLastBiometricAuthElapsedMs() {
        return prefs.getLong(PREF_BIOMETRIC_LAST_AUTH_ELAPSED_MS, -1L);
    }

    @Override
    public void setLastBiometricAuthElapsedMs(long elapsedMs) {
        prefs.edit().putLong(PREF_BIOMETRIC_LAST_AUTH_ELAPSED_MS, elapsedMs).apply();
    }

    @Override
    public int getLastBiometricAuthBootCount() {
        return prefs.getInt(PREF_BIOMETRIC_LAST_AUTH_BOOT_COUNT, -1);
    }

    @Override
    public void setLastBiometricAuthBootCount(int bootCount) {
        prefs.edit().putInt(PREF_BIOMETRIC_LAST_AUTH_BOOT_COUNT, bootCount).apply();
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
