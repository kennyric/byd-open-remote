package com.bydwatch.companion.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Lets a consumer (the patched Google Maps) ask for fresher data than the periodic widget sync
 * provides. The repository enforces a minimum gap, so a misbehaving or malicious sender cannot
 * turn this into a request loop against the BYD account.
 */
public final class VehicleRefreshRequestReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!VehicleStatusContract.ACTION_REQUEST_REFRESH.equals(action)) return;
        com.bydwatch.companion.BydWatchApp app = com.bydwatch.companion.BydWatchApp.getInstance();
        if (app == null) return;
        BydRepository repository = app.getRepository();
        if (repository != null) repository.refreshNow();
    }
}
