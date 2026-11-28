package com.bydwatch.companion.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the widget refresh schedule after a reboot or an in-place app update,
 * because AlarmManager alarms and JobScheduler jobs are cleared by both events.
 */
public class WidgetSyncBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
        }
    }
}
