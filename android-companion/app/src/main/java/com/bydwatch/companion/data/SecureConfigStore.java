package com.bydwatch.companion.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

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
    private static final String PREF_DEFAULT_TEMP_C = "byd_default_temp_c";

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
        prefs.edit().putString(PREF_VIN, vin).apply();
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
