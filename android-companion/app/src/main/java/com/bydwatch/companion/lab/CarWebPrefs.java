package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted settings for the Overdrive WebView and its Advanced screen. */
public final class CarWebPrefs {

    private static final String PREFS = "car_web";
    private static final String KEY_SHOW = "show_overdrive_entry";
    private static final String KEY_HIDE_URL = "hide_url_bar";
    private static final String KEY_BASE = "car_base_url";
    private static final String KEY_PAGE = "default_page";
    // Kept as the old key name so an existing toggle survives the rename.
    private static final String KEY_CONTROLS = "overdrive_shortcuts_enabled";
    private static final String KEY_CONTROLS_VISIBILITY = "overdrive_controls_visibility";

    /** Main-page Overdrive controls: always visible, only while connected, or never. */
    public static final String CONTROLS_ALWAYS = "always";
    public static final String CONTROLS_CONNECTED = "connected";
    public static final String CONTROLS_HIDDEN = "hidden";

    private CarWebPrefs() {}

    public static boolean showOverdrive(Context context) {
        return prefs(context).getBoolean(KEY_SHOW, false);
    }

    public static void setShowOverdrive(Context context, boolean show) {
        prefs(context).edit().putBoolean(KEY_SHOW, show).apply();
    }

    public static boolean hideUrlBar(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_URL, true);
    }

    public static void setHideUrlBar(Context context, boolean hide) {
        prefs(context).edit().putBoolean(KEY_HIDE_URL, hide).apply();
    }

    public static String baseUrl(Context context) {
        // Native Overdrive calls (login, status, controls) need the server root only.
        return CarWebUrl.origin(prefs(context).getString(KEY_BASE, ""));
    }

    /** The address exactly as entered, for the WebView entry point. */
    public static String entryUrl(Context context) {
        return CarWebUrl.normalize(prefs(context).getString(KEY_BASE, ""));
    }

    public static void setBaseUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_BASE, CarWebUrl.normalize(url)).apply();
    }

    public static String defaultPage(Context context) {
        return prefs(context).getString(KEY_PAGE, "/vehicle-control.html");
    }

    public static void setDefaultPage(Context context, String page) {
        prefs(context).edit().putString(KEY_PAGE, page != null ? page : "/vehicle-control.html").apply();
    }

    public static boolean overdriveControlsEnabled(Context context) {
        return !CONTROLS_HIDDEN.equals(overdriveControlsVisibility(context));
    }

    public static void setOverdriveControlsEnabled(Context context, boolean enabled) {
        setOverdriveControlsVisibility(context,
                enabled ? CONTROLS_CONNECTED : CONTROLS_HIDDEN);
    }

    /**
     * Three-way main-page visibility. The old on/off toggle is read as a fallback so
     * existing installs keep showing the card the way they did before.
     */
    public static String overdriveControlsVisibility(Context context) {
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString(KEY_CONTROLS_VISIBILITY, null);
        if (stored == null) {
            return prefs.getBoolean(KEY_CONTROLS, false) ? CONTROLS_CONNECTED : CONTROLS_HIDDEN;
        }
        return normalizeControlsVisibility(stored);
    }

    public static void setOverdriveControlsVisibility(Context context, String visibility) {
        prefs(context).edit()
                .putString(KEY_CONTROLS_VISIBILITY,
                        normalizeControlsVisibility(visibility))
                .apply();
    }

    static String normalizeControlsVisibility(String visibility) {
        if (CONTROLS_ALWAYS.equals(visibility)) return CONTROLS_ALWAYS;
        if (CONTROLS_CONNECTED.equals(visibility)) return CONTROLS_CONNECTED;
        return CONTROLS_HIDDEN;
    }

    public static void reset(Context context) {
        prefs(context).edit()
                .remove(KEY_SHOW)
                .remove(KEY_HIDE_URL)
                .remove(KEY_BASE)
                .remove(KEY_PAGE)
                .remove(KEY_CONTROLS)
                .remove(KEY_CONTROLS_VISIBILITY)
                .apply();
        OverdriveSession.clear(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
