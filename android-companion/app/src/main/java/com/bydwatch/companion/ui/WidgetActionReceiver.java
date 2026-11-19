package com.bydwatch.companion.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Unexported internal BroadcastReceiver handling home-screen widget button taps
 * and short-interval auto-sync alarms so exported AppWidgetProviders cannot be invoked
 * by third-party apps.
 */
public class WidgetActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (VehicleWidgetProvider.handlesAction(action)) {
            VehicleWidgetProvider.handleInternalAction(this, context, action);
        } else if (LocationWidgetProvider.handlesAction(action)) {
            LocationWidgetProvider.handleInternalAction(this, context, action);
        }
    }
}
