package com.bydwatch.companion.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * Quick Settings tile that exists only in the demo flavor (declared with enabled="${DEMO_SETUP_TILE}").
 * Tapping it pins both home-screen widgets so screenshot runs do not have to place them by hand.
 * Does nothing when the launcher has no widget-pinning support.
 */
public class DemoSetupTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        if (awm == null || !awm.isRequestPinAppWidgetSupported()) {
            Toast.makeText(this, "Pinning not supported by this launcher", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean any = requestPin(awm, VehicleWidgetProvider.class);
        any |= requestPin(awm, LocationWidgetProvider.class);
        if (!any) {
            Toast.makeText(this, "Widgets already placed", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean requestPin(AppWidgetManager awm, Class<?> provider) {
        try {
            return awm.requestPinAppWidget(new ComponentName(this, provider), (Bundle) null, null);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
