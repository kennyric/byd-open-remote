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
    private static final String KEY_SHORTCUTS = "overdrive_shortcuts_enabled";

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
        return prefs(context).getString(KEY_BASE, "");
    }

    public static void setBaseUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_BASE, url != null ? url : "").apply();
    }

    public static String defaultPage(Context context) {
        return prefs(context).getString(KEY_PAGE, "/vehicle-control.html");
    }

    public static void setDefaultPage(Context context, String page) {
        prefs(context).edit().putString(KEY_PAGE, page != null ? page : "/vehicle-control.html").apply();
    }

    public static boolean overdriveShortcutsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SHORTCUTS, false);
    }

    public static void setOverdriveShortcutsEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SHORTCUTS, enabled).apply();
    }

    public static void reset(Context context) {
        prefs(context).edit()
                .remove(KEY_SHOW)
                .remove(KEY_HIDE_URL)
                .remove(KEY_BASE)
                .remove(KEY_PAGE)
                .remove(KEY_SHORTCUTS)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
