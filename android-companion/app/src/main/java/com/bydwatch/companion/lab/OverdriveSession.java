package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.ConfigStore;

import java.util.Locale;

/**
 * The phone's Overdrive session: the one-time access code and the JWT minted
 * from it.
 *
 * <p>The access code is Overdrive's device secret. It is kept in the encrypted
 * {@link ConfigStore} (Keystore-backed) instead of plain preferences, because it
 * is equivalent in power to the operation PIN. Older installs stored it in
 * {@code car_web}; those values are migrated and removed on first read.
 */
public final class OverdriveSession {

    private static final String TAG = "OverdriveSession";
    private static final String LEGACY_PREFS = "car_web";
    private static final String KEY_ACCESS_CODE = "overdrive_access_code";
    private static final String KEY_JWT = "overdrive_jwt";
    private static final String KEY_JWT_EXPIRY = "overdrive_jwt_expiry";
    private static final String KEY_DEVICE_ID = "overdrive_device_id";

    /** Overdrive rejects anything shorter than this at the login page. */
    static final int MIN_ACCESS_CODE = 8;

    private OverdriveSession() {}

    public static boolean hasAccessCode(Context context) {
        String code = accessCode(context);
        return code != null && !code.isEmpty();
    }

    public static String accessCode(Context context) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store == null || !originMatches(context, store)) return null;
        return trimOrNull(store.getOverdriveAccessCode());
    }

    public static void setAccessCode(Context context, String code) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store != null) {
            store.setOverdriveOrigin(currentOrigin(context));
            store.setOverdriveAccessCode(code != null ? code.trim() : "");
        }
    }

    /** Matches Overdrive's own minimum-length check before it hits the network. */
    public static boolean isPlausibleAccessCode(String code) {
        return code != null && code.trim().length() >= MIN_ACCESS_CODE;
    }

    public static String jwt(Context context) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store == null || !originMatches(context, store)) return null;
        return trimOrNull(store.getOverdriveJwt());
    }

    public static void setJwt(Context context, String jwt, long expiresInSeconds, long nowMs) {
        ConfigStore store = store(context);
        if (store == null) return;
        long seconds = expiresInSeconds > 0 ? expiresInSeconds : 30L * 24 * 60 * 60;
        store.setOverdriveOrigin(currentOrigin(context));
        store.setOverdriveJwt(jwt != null ? jwt : "");
        store.setOverdriveJwtExpiry(nowMs + seconds * 1000L);
    }

    public static boolean hasJwt(Context context, long nowMs) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store == null || !originMatches(context, store)) return false;
        if (trimOrNull(store.getOverdriveJwt()) == null) return false;
        return store.getOverdriveJwtExpiry() > nowMs;
    }

    public static void clearJwt(Context context) {
        ConfigStore store = store(context);
        if (store == null) return;
        store.setOverdriveJwt("");
        store.setOverdriveJwtExpiry(0L);
    }

    public static String deviceId(Context context) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store == null || !originMatches(context, store)) return null;
        return trimOrNull(store.getOverdriveDeviceId());
    }

    public static void setDeviceId(Context context, String deviceId) {
        migrateLegacy(context);
        ConfigStore store = store(context);
        if (store != null) {
            store.setOverdriveOrigin(currentOrigin(context));
            store.setOverdriveDeviceId(deviceId != null ? deviceId.trim() : "");
        }
    }

    /** Forget the access code, JWT and remembered device. */
    public static void clear(Context context) {
        ConfigStore store = store(context);
        if (store != null) store.clearOverdriveSession();
        legacy(context).edit()
                .remove(KEY_ACCESS_CODE)
                .remove(KEY_JWT)
                .remove(KEY_JWT_EXPIRY)
                .remove(KEY_DEVICE_ID)
                .apply();
        clearWebSession();
    }

    /**
     * The car's web UI keeps its own cookie session. Disconnecting must not leave that behind,
     * or "Disconnect" would only hide the token while the WebView stays signed in.
     */
    private static void clearWebSession() {
        try {
            CookieManager cookies = CookieManager.getInstance();
            cookies.removeAllCookies(null);
            cookies.flush();
        } catch (Throwable ignored) {
            // WebView not available (tests); nothing to clear.
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
            // Same.
        }
    }

    static String maskedAccessCode(Context context) {
        String code = accessCode(context);
        if (code == null) return "";
        return String.format(Locale.ROOT, "%" + code.length() + "s", "").replace(' ', '•');
    }

    /**
     * Copy the pre-encryption {@code car_web} values once, then delete them. Kept cheap
     * enough to run on every access: the legacy keys disappear after the first migration.
     */
    private static void migrateLegacy(Context context) {
        SharedPreferences legacy = legacy(context);
        String code = legacy.getString(KEY_ACCESS_CODE, "");
        String jwt = legacy.getString(KEY_JWT, "");
        long expiry = legacy.getLong(KEY_JWT_EXPIRY, 0L);
        String device = legacy.getString(KEY_DEVICE_ID, "");
        if (isBlank(code) && isBlank(jwt) && expiry == 0L && isBlank(device)) return;

        ConfigStore store = store(context);
        if (store == null) return;
        if (isBlank(store.getOverdriveAccessCode()) && !isBlank(code)) {
            store.setOverdriveAccessCode(code.trim());
        }
        if (isBlank(store.getOverdriveJwt()) && !isBlank(jwt)) {
            store.setOverdriveJwt(jwt.trim());
            if (expiry > 0L) store.setOverdriveJwtExpiry(expiry);
        }
        if (isBlank(store.getOverdriveDeviceId()) && !isBlank(device)) {
            store.setOverdriveDeviceId(device.trim());
        }
        if (isBlank(store.getOverdriveOrigin())) {
            store.setOverdriveOrigin(currentOrigin(context));
        }
        legacy.edit()
                .remove(KEY_ACCESS_CODE)
                .remove(KEY_JWT)
                .remove(KEY_JWT_EXPIRY)
                .remove(KEY_DEVICE_ID)
                .apply();
    }

    private static ConfigStore store(Context context) {
        try {
            BydWatchApp app = BydWatchApp.getInstance();
            return app != null ? app.getConfigStore() : null;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Encrypted config store unavailable; Overdrive session disabled", unavailable);
            return null;
        }
    }

    private static boolean originMatches(Context context, ConfigStore store) {
        String stored = trimOrNull(store.getOverdriveOrigin());
        if (stored == null) return true;
        return stored.equalsIgnoreCase(currentOrigin(context));
    }

    private static String currentOrigin(Context context) {
        return CarWebUrl.origin(CarWebPrefs.baseUrl(context));
    }

    private static SharedPreferences legacy(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
