package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * The phone's Overdrive session: the one-time access code and the JWT minted
 * from it.
 *
 * <p>The access code is Overdrive's device secret. It never leaves the phone
 * except to {@code POST /auth/token} on the car's own HTTP server.
 */
public final class OverdriveSession {

    private static final String PREFS = "car_web";
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
        String code = prefs(context).getString(KEY_ACCESS_CODE, "");
        return code == null || code.trim().isEmpty() ? null : code.trim();
    }

    public static void setAccessCode(Context context, String code) {
        String value = code != null ? code.trim() : "";
        prefs(context).edit().putString(KEY_ACCESS_CODE, value).apply();
    }

    /** Matches Overdrive's own minimum-length check before it hits the network. */
    public static boolean isPlausibleAccessCode(String code) {
        return code != null && code.trim().length() >= MIN_ACCESS_CODE;
    }

    public static String jwt(Context context) {
        String jwt = prefs(context).getString(KEY_JWT, "");
        return jwt == null || jwt.trim().isEmpty() ? null : jwt.trim();
    }

    public static void setJwt(Context context, String jwt, long expiresInSeconds, long nowMs) {
        long seconds = expiresInSeconds > 0 ? expiresInSeconds : 30L * 24 * 60 * 60;
        prefs(context).edit()
                .putString(KEY_JWT, jwt != null ? jwt : "")
                .putLong(KEY_JWT_EXPIRY, nowMs + seconds * 1000L)
                .apply();
    }

    public static boolean hasJwt(Context context, long nowMs) {
        if (jwt(context) == null) return false;
        long expiry = prefs(context).getLong(KEY_JWT_EXPIRY, 0L);
        return expiry == 0L || expiry > nowMs;
    }

    public static void clearJwt(Context context) {
        prefs(context).edit()
                .remove(KEY_JWT)
                .remove(KEY_JWT_EXPIRY)
                .apply();
    }

    public static String deviceId(Context context) {
        String id = prefs(context).getString(KEY_DEVICE_ID, "");
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }

    public static void setDeviceId(Context context, String deviceId) {
        prefs(context).edit()
                .putString(KEY_DEVICE_ID, deviceId != null ? deviceId.trim() : "")
                .apply();
    }

    /** Forget the access code, JWT and remembered device. */
    public static void clear(Context context) {
        prefs(context).edit()
                .remove(KEY_ACCESS_CODE)
                .remove(KEY_JWT)
                .remove(KEY_JWT_EXPIRY)
                .remove(KEY_DEVICE_ID)
                .apply();
    }

    static String maskedAccessCode(Context context) {
        String code = accessCode(context);
        if (code == null) return "";
        return String.format(Locale.ROOT, "%" + code.length() + "s", "").replace(' ', '•');
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
