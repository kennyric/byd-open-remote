package com.bydwatch.companion.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchProtocol;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Compact 4x1 Material You home-screen widget showing cached car GPS coordinates
 * and prompting to redirect into navigation apps when tapped.
 */
public class LocationWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_LOCATION_REFRESH = "com.bydwatch.companion.LOCATION_WIDGET_REFRESH";
    public static final String ACTION_LOCATION_NAVIGATE = "com.bydwatch.companion.LOCATION_WIDGET_NAVIGATE";

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        if (!VehicleWidgetProvider.hasActiveWidgets(context)) {
            WidgetSyncJobService.cancelPeriodicSync(context);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        int[] owned = ownedIds(context, appWidgetManager, appWidgetIds);
        if (owned.length == 0) return;
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
        updateLocationWidgets(context, appWidgetManager, owned);
    }

    private static int[] ownedIds(Context context, AppWidgetManager manager, int[] candidates) {
        if (context == null || manager == null || candidates == null) return new int[0];
        int[] owned = manager.getAppWidgetIds(
                new ComponentName(context, LocationWidgetProvider.class));
        if (owned == null || owned.length == 0) return new int[0];
        java.util.ArrayList<Integer> keep = new java.util.ArrayList<>();
        for (int candidate : candidates) {
            for (int id : owned) {
                if (id == candidate) {
                    keep.add(candidate);
                    break;
                }
            }
        }
        int[] result = new int[keep.size()];
        for (int i = 0; i < result.length; i++) result[i] = keep.get(i);
        return result;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // Custom widget actions are routed exclusively through unexported WidgetActionReceiver.
    }

    static boolean handlesAction(String act) {
        return ACTION_LOCATION_REFRESH.equals(act) || ACTION_LOCATION_NAVIGATE.equals(act);
    }

    static void handleInternalAction(android.content.BroadcastReceiver receiver, Context context, String act) {
        if (ACTION_LOCATION_REFRESH.equals(act)) {
            VehicleWidgetProvider.executeActionAsync(receiver, context, WatchProtocol.ACTION_LOCATION, true);
        } else if (ACTION_LOCATION_NAVIGATE.equals(act)) {
            VehicleStatus st = VehicleWidgetProvider.resolveStatus(context);
            Intent prompt = new Intent(context, NavigationPromptActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (st != null && st.hasLocation()) {
                BydWatchApp app = VehicleWidgetProvider.resolveApp(context);
                ConfigStore cfg = app != null ? app.getConfigStore() : null;
                String preferred = VehicleWidgetProvider.resolvePreferredModelName(cfg);
                String navLabel = preferred != null ? preferred : st.modelName;
                prompt.putExtra(NavigationPromptActivity.EXTRA_LAT, st.latitude);
                prompt.putExtra(NavigationPromptActivity.EXTRA_LNG, st.longitude);
                prompt.putExtra(NavigationPromptActivity.EXTRA_LABEL, navLabel);
            }
            context.startActivity(prompt);
        }
    }

    public static boolean hasActiveWidgets(Context context) {
        if (context == null) return false;
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, LocationWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    public static void refreshWidgets(Context context) {
        if (context == null) return;
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, LocationWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            updateLocationWidgets(context, mgr, ids);
        }
    }

    static void updateLocationWidgets(Context context, AppWidgetManager mgr, int[] ids) {
        BydWatchApp app = VehicleWidgetProvider.resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        Context resCtx = AppLocaleHelper.localizedContext(context, cfg);
        VehicleStatus st = VehicleWidgetProvider.resolveStatus(context);
        String banner = VehicleWidgetProvider.getActiveBanner(context);

        String titleText;
        if (banner != null) {
            titleText = banner;
        } else if (st != null && st.hasLocation()) {
            String timeSuffix = "";
            long lastSyncMs = cfg != null ? cfg.getWidgetLastSyncMs() : 0L;
            long gpsMs = 0L;
            if (st.gpsTs != null && st.gpsTs > 0) {
                gpsMs = st.gpsTs > 1_000_000_000_000L ? st.gpsTs : st.gpsTs * 1000L;
            }
            long displayMs = Math.max(lastSyncMs, gpsMs);
            if (displayMs > 0) {
                timeSuffix = " · " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(displayMs));
            }
            String preferred = VehicleWidgetProvider.resolvePreferredModelName(cfg);
            String name = preferred != null
                    ? preferred
                    : ((st.modelName != null && !st.modelName.trim().isEmpty())
                            ? st.modelName.trim()
                            : resCtx.getString(R.string.location_widget_label));
            titleText = name + timeSuffix + resCtx.getString(R.string.widget_location_navigate_suffix);
        } else {
            titleText = resCtx.getString(R.string.location_widget_label);
        }

        String coordsText;
        if (st != null && st.hasLocation()) {
            String cachedStreet = VehicleLocationHelper.getCachedStreetName(st.latitude, st.longitude);
            coordsText = cachedStreet != null
                    ? cachedStreet
                    : VehicleLocationHelper.formatCoordinates(st.latitude, st.longitude);
            if (cachedStreet == null) {
                VehicleLocationHelper.resolveStreetName(context, st.latitude, st.longitude,
                        street -> refreshWidgets(context));
            }
        } else {
            coordsText = resCtx.getString(R.string.location_widget_empty);
        }

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent navPi = PendingIntent.getBroadcast(context, 21,
                new Intent(context, WidgetActionReceiver.class).setAction(ACTION_LOCATION_NAVIGATE), flags);
        boolean requireSyncAuth = BiometricLockHelper.isWidgetProtectionEnabled(cfg)
                && cfg != null && cfg.isBiometricWidgetSyncRequired();
        PendingIntent syncPi = requireSyncAuth
                ? PendingIntent.getActivity(context, 122,
                        WidgetAuthActivity.createIntent(context, ACTION_LOCATION_REFRESH), flags)
                : PendingIntent.getBroadcast(context, 22,
                        new Intent(context, WidgetActionReceiver.class).setAction(ACTION_LOCATION_REFRESH), flags);

        for (int appWidgetId : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_location);
            views.setTextViewText(R.id.tvLocationTitle, titleText);
            views.setTextViewText(R.id.tvLocationCoords, coordsText);
            views.setTextViewText(R.id.btnLocationSync, resCtx.getString(R.string.btn_sync));
            views.setOnClickPendingIntent(R.id.layoutLocationBody, navPi);
            views.setOnClickPendingIntent(R.id.btnLocationSync, syncPi);
            mgr.updateAppWidget(appWidgetId, views);
        }
    }
}
