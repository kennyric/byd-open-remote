package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.bydwatch.companion.data.ConfigStore;

import java.util.Locale;

/**
 * Applies user-selected in-app locale (System, English, Bahasa Indonesia) across
 * AppCompat activities and non-Activity surfaces such as home-screen widgets and tiles.
 */
public final class AppLocaleHelper {

    private AppLocaleHelper() {}

    @NonNull
    public static LocaleListCompat toLocaleListCompat(@Nullable String language) {
        String normalized = ConfigStore.normalizeAppLanguage(language);
        if (ConfigStore.LANG_ID.equals(normalized)) {
            return LocaleListCompat.forLanguageTags("id");
        }
        if (ConfigStore.LANG_EN.equals(normalized)) {
            return LocaleListCompat.forLanguageTags("en");
        }
        return LocaleListCompat.getEmptyLocaleList();
    }

    /**
     * Updates AppCompat application locales only when the target differs from the active setting,
     * avoiding redundant activity recreations during startup.
     */
    public static void applyLanguage(@Nullable String language) {
        LocaleListCompat desired = toLocaleListCompat(language);
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (!desired.equals(current)) {
            AppCompatDelegate.setApplicationLocales(desired);
        }
    }

    @NonNull
    public static String resolveEffectiveLanguage(@Nullable ConfigStore configStore) {
        if (configStore != null) {
            String stored = ConfigStore.normalizeAppLanguage(configStore.getAppLanguage());
            if (!ConfigStore.LANG_SYSTEM.equals(stored)) {
                return stored;
            }
        }
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        if (!appLocales.isEmpty()) {
            Locale first = appLocales.get(0);
            if (first != null) {
                return ConfigStore.normalizeAppLanguage(first.getLanguage());
            }
        }
        return ConfigStore.LANG_SYSTEM;
    }

    /**
     * Returns a Context configured with the selected app language so AppWidgetProviders,
     * TileServices, and notifications resolve localized strings even on API &lt; 33.
     */
    @NonNull
    public static Context localizedContext(@NonNull Context base, @Nullable ConfigStore configStore) {
        String lang = resolveEffectiveLanguage(configStore);
        if (!ConfigStore.LANG_ID.equals(lang) && !ConfigStore.LANG_EN.equals(lang)) {
            return base;
        }
        Locale target = ConfigStore.LANG_ID.equals(lang)
                ? Locale.forLanguageTag("id")
                : Locale.ENGLISH;
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocales(new LocaleList(target));
        conf.setLocale(target);
        Context wrapped = base.createConfigurationContext(conf);
        return wrapped != null ? wrapped : base;
    }
}
