package com.bydwatch.companion.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsive Material You home-screen widget with 30-minute background auto-sync
 * and direct background controls without opening the app:
 * - 2x2 grid: Battery, Range, plus large Lock / Unlock / Sync buttons.
 * - 4x2 - 5x2 grid: Last Sync Time, Battery, Range, AC Status, plus Lock / Unlock / AC Toggle / Sync.
 */
public class VehicleWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_WIDGET_REFRESH = "com.bydwatch.companion.WIDGET_REFRESH";
    public static final String ACTION_WIDGET_LOCK = "com.bydwatch.companion.WIDGET_LOCK";
    public static final String ACTION_WIDGET_UNLOCK = "com.bydwatch.companion.WIDGET_UNLOCK";
    public static final String ACTION_WIDGET_AC = "com.bydwatch.companion.WIDGET_AC";
    public static final String ACTION_WIDGET_AUTO_SYNC = "com.bydwatch.companion.WIDGET_AUTO_SYNC";

    static final int WIDE_WIDGET_MIN_WIDTH_DP = 220;
    static final int TALL_WIDGET_MIN_HEIGHT_DP = 175;

    private static final String PREFS_WIDGET = "byd_widget_prefs";
    private static final String KEY_LAST_STATUS_JSON = "last_status_json";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_BANNER_TEXT = "banner_text";
    private static final String KEY_BANNER_EXPIRES_MS = "banner_expires_ms";

    private static final long BANNER_TTL_MS = 18_000L;
    private static final long GO_ASYNC_GUARD_MS = 8_500L;
    private static final AtomicLong CMD_SEQ = new AtomicLong();

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        if (!LocationWidgetProvider.hasActiveWidgets(context)) {
            WidgetSyncJobService.cancelPeriodicSync(context);
            clearTransientBanner(context);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context,
            AppWidgetManager appWidgetManager,
            int appWidgetId,
            Bundle newOptions
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateAllWidgets(context, appWidgetManager, new int[]{appWidgetId});
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
        updateAllWidgets(context, appWidgetManager, appWidgetIds);

        if (isAutoSyncNeeded(context)) {
            executeActionAsync(this, context, WatchProtocol.ACTION_STATUS, false);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // Custom widget actions are routed exclusively through unexported WidgetActionReceiver.
    }

    static boolean handlesAction(String act) {
        return ACTION_WIDGET_AUTO_SYNC.equals(act)
                || ACTION_WIDGET_REFRESH.equals(act)
                || ACTION_WIDGET_LOCK.equals(act)
                || ACTION_WIDGET_UNLOCK.equals(act)
                || ACTION_WIDGET_AC.equals(act);
    }

    static void handleInternalAction(BroadcastReceiver receiver, Context context, String act) {
        if (ACTION_WIDGET_AUTO_SYNC.equals(act)) {
            if (WidgetSyncJobService.hasAnyActiveWidgets(context)) {
                executeActionAsync(receiver, context, WatchProtocol.ACTION_STATUS, false);
                WidgetSyncJobService.rescheduleShortAlarmIfNeeded(context);
            } else {
                WidgetSyncJobService.cancelPeriodicSync(context);
            }
            return;
        }
        String watchAction = null;
        if (ACTION_WIDGET_REFRESH.equals(act)) {
            watchAction = WatchProtocol.ACTION_STATUS;
        } else if (ACTION_WIDGET_LOCK.equals(act)) {
            watchAction = WatchProtocol.ACTION_LOCK;
        } else if (ACTION_WIDGET_UNLOCK.equals(act)) {
            watchAction = WatchProtocol.ACTION_UNLOCK;
        } else if (ACTION_WIDGET_AC.equals(act)) {
            VehicleStatus st = resolveStatus(context);
            watchAction = (st != null && st.acOn)
                    ? WatchProtocol.ACTION_AC_OFF
                    : WatchProtocol.ACTION_AC_ON;
        }

        if (watchAction != null) {
            executeActionAsync(receiver, context, watchAction, true);
        }
    }

    public static boolean hasActiveWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VehicleWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    public static void refreshWidgets(Context context) {
        if (context == null) return;
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus live = repo != null ? repo.getCachedStatus() : null;
        if (live != null) {
            persistStatus(context, live);
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VehicleWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            updateAllWidgets(context, mgr, ids);
        }
        LocationWidgetProvider.refreshWidgets(context);
    }

    /**
     * Executes a single widget action in the background via BydRepository,
     * persists returned telemetry, and refreshes all home-screen widgets.
     */
    public static boolean executeWidgetAction(
            Context context,
            String watchAction,
            boolean showProgressBanner,
            @Nullable Runnable onComplete
    ) {
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (repo == null || cfg == null || !cfg.isConfigured()) {
            if (showProgressBanner) {
                setTransientBanner(context, "Setup app");
            }
            refreshWidgets(context);
            return false;
        }

        if (showProgressBanner) {
            setTransientBanner(context, progressLabelForAction(watchAction));
            refreshWidgets(context);
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("id", "wdg_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet());
            cmd.put("action", watchAction);
            if (WatchProtocol.ACTION_AC_ON.equals(watchAction)) {
                cmd.put("tempC", cfg.getDefaultTempC());
            }
            repo.handleWatchJson(cmd.toString(), respStr -> {
                try {
                    WatchResponse resp = WatchResponse.fromJson(new JSONObject(respStr));
                    if (resp != null && resp.ok && resp.state != null) {
                        persistStatus(context, resp.state);
                        clearTransientBanner(context);
                    } else if (showProgressBanner) {
                        String msg = resp != null && resp.msg != null && !resp.msg.trim().isEmpty()
                                ? resp.msg.trim()
                                : (resp != null && resp.err != null ? resp.err : "Failed");
                        setTransientBanner(context, msg);
                    }
                    refreshWidgets(context);
                } catch (Exception e) {
                    if (showProgressBanner) {
                        setTransientBanner(context, "Sync failed");
                        refreshWidgets(context);
                    }
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            });
            return true;
        } catch (Exception e) {
            if (showProgressBanner) {
                setTransientBanner(context, "Error");
                refreshWidgets(context);
            }
            return false;
        }
    }

    static void executeActionAsync(
            BroadcastReceiver receiver,
            Context context,
            String watchAction,
            boolean showProgressBanner
    ) {
        PendingResult pendingResult = receiver.goAsync();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true) && pendingResult != null) {
                try {
                    pendingResult.finish();
                } catch (Exception ignored) {}
            }
        };

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(finishOnce, GO_ASYNC_GUARD_MS);

        boolean started = executeWidgetAction(context, watchAction, showProgressBanner, () -> {
            mainHandler.removeCallbacks(finishOnce);
            finishOnce.run();
        });
        if (!started) {
            mainHandler.removeCallbacks(finishOnce);
            finishOnce.run();
        }
    }

    private static boolean isAutoSyncNeeded(Context context) {
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg == null || !cfg.isConfigured()) {
            return false;
        }
        long lastSyncMs = cfg.getWidgetLastSyncMs();
        int intervalMin = WidgetSyncJobService.getConfiguredIntervalMinutes(context);
        long intervalMs = intervalMin * 60L * 1000L;
        long staleMs = intervalMin >= 5 ? (intervalMs - 60_000L) : Math.max(30_000L, intervalMs / 2L);
        return lastSyncMs <= 0L || (System.currentTimeMillis() - lastSyncMs) >= staleMs;
    }

    private static String progressLabelForAction(String action) {
        if (WatchProtocol.ACTION_LOCK.equals(action)) return "Locking...";
        if (WatchProtocol.ACTION_UNLOCK.equals(action)) return "Unlocking...";
        if (WatchProtocol.ACTION_AC_ON.equals(action)) return "AC On...";
        if (WatchProtocol.ACTION_AC_OFF.equals(action)) return "AC Off...";
        if (WatchProtocol.ACTION_LOCATION.equals(action)) return "Locating...";
        return "Syncing...";
    }

    @Nullable
    private static BydWatchApp resolveApp(Context context) {
        BydWatchApp app = BydWatchApp.getInstance();
        if (app == null && context != null && context.getApplicationContext() instanceof BydWatchApp) {
            app = (BydWatchApp) context.getApplicationContext();
        }
        return app;
    }

    @Nullable
    static VehicleStatus resolveStatus(Context context) {
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus st = repo != null ? repo.getCachedStatus() : null;
        if (st != null) {
            return st;
        }
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        String raw = cfg != null ? cfg.getWidgetStatusJson() : null;
        if ((raw == null || raw.isEmpty()) && context != null) {
            SharedPreferences legacy = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
            String legacyJson = legacy.getString(KEY_LAST_STATUS_JSON, null);
            long legacyTs = legacy.getLong(KEY_LAST_SYNC_MS, 0L);
            if (legacyJson != null && !legacyJson.isEmpty()) {
                raw = legacyJson;
                if (cfg != null) {
                    cfg.setWidgetStatusJson(legacyJson);
                    if (legacyTs > 0L) {
                        cfg.setWidgetLastSyncMs(legacyTs);
                    }
                }
                legacy.edit().remove(KEY_LAST_STATUS_JSON).remove(KEY_LAST_SYNC_MS).apply();
            }
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            VehicleStatus restored = VehicleStatus.fromJson(new JSONObject(raw));
            if (restored != null && repo != null) {
                repo.setCachedStatus(restored);
            }
            return restored;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void persistStatus(Context context, VehicleStatus status) {
        if (context == null || status == null) return;
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg != null) {
            VehicleStatus sanitized = status.toBuilder()
                    .vin("")
                    .vehicles(Collections.emptyList())
                    .build();
            cfg.setWidgetStatusJson(sanitized.toJson().toString());
            cfg.setWidgetLastSyncMs(System.currentTimeMillis());
        }
        SharedPreferences legacy = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        if (legacy.contains(KEY_LAST_STATUS_JSON) || legacy.contains(KEY_LAST_SYNC_MS)) {
            legacy.edit().remove(KEY_LAST_STATUS_JSON).remove(KEY_LAST_SYNC_MS).apply();
        }
    }

    private static void setTransientBanner(Context context, String text) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_BANNER_TEXT, text)
                .putLong(KEY_BANNER_EXPIRES_MS, System.currentTimeMillis() + BANNER_TTL_MS)
                .apply();
    }

    private static void clearTransientBanner(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_BANNER_TEXT).remove(KEY_BANNER_EXPIRES_MS).apply();
    }

    @Nullable
    static String getActiveBanner(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        String text = prefs.getString(KEY_BANNER_TEXT, null);
        long expires = prefs.getLong(KEY_BANNER_EXPIRES_MS, 0L);
        if (text == null || text.isEmpty() || System.currentTimeMillis() > expires) {
            return null;
        }
        return text;
    }

    static boolean isWideWidget(AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
        int minWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        return minWidthDp >= WIDE_WIDGET_MIN_WIDTH_DP;
    }

    static boolean isTallWidget(AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
        int minHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
        return minHeightDp >= TALL_WIDGET_MIN_HEIGHT_DP;
    }

    private static void updateAllWidgets(Context context, AppWidgetManager mgr, int[] ids) {
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        VehicleStatus st = resolveStatus(context);
        String banner = getActiveBanner(context);

        String distUnit = cfg != null ? cfg.getDistanceUnit() : UnitPreferences.DIST_KM;
        String socText = st != null ? (st.soc + "%") : "--%";
        String rangeText = st != null
                ? UnitPreferences.formatDistance(st.rangeKm, distUnit)
                : "-- " + UnitPreferences.normalizeDistanceUnit(distUnit);

        long lastSyncMs = cfg != null ? cfg.getWidgetLastSyncMs() : 0L;
        String lastSyncText = lastSyncMs > 0L
                ? "Last sync " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(lastSyncMs))
                : "Last sync --:--";

        String acStatusText = st == null ? "AC --" : (st.acOn ? "AC On" : "AC Off");
        String acBtnText = (st != null && st.acOn) ? "AC Off" : "AC On";

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent openPi = PendingIntent.getActivity(context, 10, new Intent(context, MainActivity.class), flags);
        PendingIntent refreshPi = PendingIntent.getBroadcast(context, 11,
                new Intent(context, WidgetActionReceiver.class).setAction(ACTION_WIDGET_REFRESH), flags);
        PendingIntent lockPi = PendingIntent.getBroadcast(context, 12,
                new Intent(context, WidgetActionReceiver.class).setAction(ACTION_WIDGET_LOCK), flags);
        PendingIntent unlockPi = PendingIntent.getBroadcast(context, 14,
                new Intent(context, WidgetActionReceiver.class).setAction(ACTION_WIDGET_UNLOCK), flags);
        PendingIntent acPi = PendingIntent.getBroadcast(context, 13,
                new Intent(context, WidgetActionReceiver.class).setAction(ACTION_WIDGET_AC), flags);
        PendingIntent navPi = PendingIntent.getBroadcast(context, 15,
                new Intent(context, WidgetActionReceiver.class).setAction(LocationWidgetProvider.ACTION_LOCATION_NAVIGATE), flags);

        boolean anyTall = false;
        for (int appWidgetId : ids) {
            boolean wide = isWideWidget(mgr, appWidgetId);
            boolean tall = isTallWidget(mgr, appWidgetId);
            if (tall) anyTall = true;
            RemoteViews views = new RemoteViews(
                    context.getPackageName(),
                    wide ? R.layout.widget_vehicle : R.layout.widget_vehicle_2x2
            );

            views.setTextViewText(R.id.tvWidgetSoc, socText);
            views.setOnClickPendingIntent(R.id.widgetHeader, openPi);
            views.setOnClickPendingIntent(R.id.btnWidgetLock, lockPi);
            views.setOnClickPendingIntent(R.id.btnWidgetUnlock, unlockPi);
            views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPi);

            if (tall) {
                views.setViewVisibility(R.id.layoutWidgetLocationBar, View.VISIBLE);
                views.setOnClickPendingIntent(R.id.layoutWidgetLocationBar, navPi);
                String timeSuffix = "";
                if (st != null && st.hasLocation() && st.gpsTs != null && st.gpsTs > 0) {
                    long ms = st.gpsTs > 1_000_000_000_000L ? st.gpsTs : st.gpsTs * 1000L;
                    timeSuffix = " · " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
                }
                String locLabel = wide ? ("Car Location" + timeSuffix + " · Navigate") : ("Location" + timeSuffix);
                views.setTextViewText(R.id.tvWidgetLocationLabel, locLabel);
                String locText;
                if (st != null && st.hasLocation()) {
                    String cachedStreet = VehicleLocationHelper.getCachedStreetName(st.latitude, st.longitude);
                    locText = cachedStreet != null
                            ? cachedStreet
                            : VehicleLocationHelper.formatCoordinates(st.latitude, st.longitude);
                } else {
                    locText = context.getString(R.string.location_widget_empty);
                }
                views.setTextViewText(R.id.tvWidgetLocationText, locText);
            } else {
                views.setViewVisibility(R.id.layoutWidgetLocationBar, View.GONE);
            }

            if (wide) {
                views.setTextViewText(R.id.tvWidgetRange, rangeText);
                views.setTextViewText(R.id.tvWidgetAcStatus, acStatusText);
                views.setTextViewText(R.id.tvWidgetLastSync, lastSyncText);
                views.setTextViewText(R.id.btnWidgetAc, acBtnText);
                views.setOnClickPendingIntent(R.id.btnWidgetAc, acPi);
                String idleStatus;
                if (st == null) {
                    idleStatus = "Tap Sync";
                } else {
                    String lockText = st.locked ? "Locked" : "Unlocked";
                    if (st.isCharging()) {
                        String power = st.hasChargePowerKw()
                                ? String.format(Locale.US, "%.1f kW", st.chargePowerKw)
                                : "";
                        String rem = formatWidgetChargeEta(st.chargeRemainingMinutes);
                        if (!power.isEmpty() && !rem.isEmpty()) {
                            idleStatus = "⚡ " + power + " · " + rem + " · " + lockText;
                        } else if (!power.isEmpty()) {
                            idleStatus = "⚡ Charging · " + power + " · " + lockText;
                        } else if (!rem.isEmpty()) {
                            idleStatus = "⚡ Charging · " + rem + " · " + lockText;
                        } else {
                            idleStatus = "⚡ Charging · " + lockText;
                        }
                    } else if (st.chargingState == VehicleStatus.CHARGING_CONNECTED) {
                        idleStatus = "Plugged In · " + lockText;
                    } else {
                        idleStatus = lockText;
                    }
                }
                views.setViewVisibility(R.id.tvWidgetBanner, View.VISIBLE);
                views.setTextViewText(R.id.tvWidgetBanner, banner != null ? banner : idleStatus);
            } else {
                String compactSubtitle = rangeText;
                if (st != null && st.isCharging()) {
                    String power = st.hasChargePowerKw()
                            ? String.format(Locale.US, "%.1f kW", st.chargePowerKw)
                            : "";
                    String rem = formatWidgetChargeEta(st.chargeRemainingMinutes);
                    if (!power.isEmpty() && !rem.isEmpty()) {
                        compactSubtitle = rangeText + "\n⚡ " + power + " · " + rem;
                    } else if (!power.isEmpty()) {
                        compactSubtitle = rangeText + "\n⚡ " + power;
                    } else if (!rem.isEmpty()) {
                        compactSubtitle = rangeText + "\n⚡ " + rem;
                    } else {
                        compactSubtitle = rangeText + " · ⚡";
                    }
                } else if (st != null && st.chargingState == VehicleStatus.CHARGING_CONNECTED) {
                    compactSubtitle = rangeText + " · Plugged";
                }
                views.setTextViewText(R.id.tvWidgetRange, banner != null ? banner : compactSubtitle);
            }

            mgr.updateAppWidget(appWidgetId, views);
        }
        if (anyTall && st != null && st.hasLocation()
                && VehicleLocationHelper.getCachedStreetName(st.latitude, st.longitude) == null) {
            VehicleLocationHelper.resolveStreetName(context, st.latitude, st.longitude,
                    street -> refreshWidgets(context));
        }
    }

    private static String formatWidgetChargeEta(Integer totalMinutes) {
        if (totalMinutes == null || totalMinutes <= 0) return "";
        int hrs = totalMinutes / 60;
        int mins = totalMinutes % 60;
        return hrs + "h " + mins + "m";
    }
}
