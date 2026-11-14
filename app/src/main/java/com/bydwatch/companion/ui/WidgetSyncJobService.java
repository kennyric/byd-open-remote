package com.bydwatch.companion.ui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.protocol.WatchProtocol;

/**
 * Background scheduler that refreshes cached EV telemetry for home-screen widgets
 * at the user-configured interval (minutes or hours) without opening the app.
 * Intervals >= 15 min use JobScheduler; shorter intervals chain AlarmManager wakeups.
 */
public class WidgetSyncJobService extends JobService {

    public static final int JOB_ID_PERIODIC_SYNC = 4101;
    public static final int ALARM_REQ_AUTO_SYNC = 4102;
    public static final int MIN_JOB_SCHEDULER_MINUTES = 15;
    public static final long PERIOD_MS = ConfigStore.DEFAULT_WIDGET_SYNC_MINUTES * 60L * 1000L;

    public static int getConfiguredIntervalMinutes(Context context) {
        BydWatchApp app = BydWatchApp.getInstance();
        if (app == null && context != null && context.getApplicationContext() instanceof BydWatchApp) {
            app = (BydWatchApp) context.getApplicationContext();
        }
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg == null) {
            return ConfigStore.DEFAULT_WIDGET_SYNC_MINUTES;
        }
        int minutes = cfg.getWidgetSyncIntervalMinutes();
        return Math.max(ConfigStore.MIN_WIDGET_SYNC_MINUTES,
                Math.min(ConfigStore.MAX_WIDGET_SYNC_MINUTES, minutes));
    }

    public static boolean hasAnyActiveWidgets(Context context) {
        return VehicleWidgetProvider.hasActiveWidgets(context)
                || LocationWidgetProvider.hasActiveWidgets(context);
    }

    public static void ensurePeriodicSyncScheduled(Context context) {
        scheduleInternal(context, false);
    }

    public static void reschedulePeriodicSync(Context context) {
        scheduleInternal(context, true);
    }

    static void rescheduleShortAlarmIfNeeded(Context context) {
        if (context == null) return;
        if (!hasAnyActiveWidgets(context)) {
            cancelPeriodicSync(context);
            return;
        }
        int intervalMin = getConfiguredIntervalMinutes(context);
        if (intervalMin < MIN_JOB_SCHEDULER_MINUTES) {
            scheduleShortIntervalAlarm(context, intervalMin * 60L * 1000L, true);
        } else {
            cancelShortIntervalAlarm(context);
            ensurePeriodicSyncScheduled(context);
        }
    }

    private static void scheduleInternal(Context context, boolean forceReschedule) {
        if (context == null) return;
        if (!hasAnyActiveWidgets(context)) {
            cancelPeriodicSync(context);
            return;
        }
        int intervalMin = getConfiguredIntervalMinutes(context);
        long intervalMs = intervalMin * 60L * 1000L;

        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (intervalMin >= MIN_JOB_SCHEDULER_MINUTES) {
            cancelShortIntervalAlarm(context);
            if (scheduler == null) return;
            JobInfo pending = scheduler.getPendingJob(JOB_ID_PERIODIC_SYNC);
            if (!forceReschedule && pending != null && pending.getIntervalMillis() == intervalMs) {
                return;
            }
            scheduler.cancel(JOB_ID_PERIODIC_SYNC);
            ComponentName component = new ComponentName(context, WidgetSyncJobService.class);
            JobInfo info = new JobInfo.Builder(JOB_ID_PERIODIC_SYNC, component)
                    .setPeriodic(intervalMs)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(info);
        } else {
            if (scheduler != null) {
                scheduler.cancel(JOB_ID_PERIODIC_SYNC);
            }
            scheduleShortIntervalAlarm(context, intervalMs, forceReschedule);
        }
    }

    private static void scheduleShortIntervalAlarm(Context context, long intervalMs, boolean forceReschedule) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        int noCreateFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_NO_CREATE;
        Intent intent = new Intent(context, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AUTO_SYNC);
        PendingIntent existing = PendingIntent.getBroadcast(context, ALARM_REQ_AUTO_SYNC, intent, noCreateFlags);
        if (!forceReschedule && existing != null) {
            return;
        }

        int updateFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQ_AUTO_SYNC, intent, updateFlags);
        long triggerAt = SystemClock.elapsedRealtime() + intervalMs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
        }
    }

    private static void cancelShortIntervalAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        int noCreateFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_NO_CREATE;
        Intent intent = new Intent(context, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AUTO_SYNC);
        PendingIntent existing = PendingIntent.getBroadcast(context, ALARM_REQ_AUTO_SYNC, intent, noCreateFlags);
        if (existing != null) {
            if (am != null) {
                am.cancel(existing);
            }
            existing.cancel();
        }
    }

    public static void cancelPeriodicSync(Context context) {
        if (context == null) return;
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID_PERIODIC_SYNC);
        }
        cancelShortIntervalAlarm(context);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Context appCtx = getApplicationContext();
        if (!hasAnyActiveWidgets(appCtx)) {
            cancelPeriodicSync(appCtx);
            return false;
        }
        return VehicleWidgetProvider.executeWidgetAction(
                appCtx,
                WatchProtocol.ACTION_STATUS,
                false,
                () -> jobFinished(params, false)
        );
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
