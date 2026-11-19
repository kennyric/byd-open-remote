package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Android Quick Settings tile showing live EV lock state and toggling Lock/Unlock or opening the app.
 */
public class QuickControlTileService extends TileService {

    private static final AtomicLong CMD_SEQ = new AtomicLong();

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTileFromCache();
    }

    @Override
    public void onClick() {
        super.onClick();
        BydWatchApp app = BydWatchApp.getInstance();
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        BydRepository repo = app != null ? app.getRepository() : null;

        if (cfg == null || repo == null || !cfg.isConfigured()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(intent);
            return;
        }

        VehicleStatus cached = repo.getCachedStatus();
        String action = (cached != null && !cached.locked)
                ? WatchProtocol.ACTION_LOCK
                : WatchProtocol.ACTION_STATUS;

        Context resCtx = AppLocaleHelper.localizedContext(this, cfg);
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setSubtitle(resCtx.getString(R.string.qs_working));
            tile.updateTile();
        }

        try {
            JSONObject cmd = new JSONObject();
            cmd.put("id", "qs_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet());
            cmd.put("action", action);
            repo.handleWatchJson(cmd.toString(), respStr -> {
                try {
                    WatchResponse.fromJson(new JSONObject(respStr));
                } catch (Exception ignored) {}
                updateTileFromCache();
            });
        } catch (Exception ignored) {
            updateTileFromCache();
        }
    }

    private void updateTileFromCache() {
        Tile tile = getQsTile();
        if (tile == null) return;
        BydWatchApp app = BydWatchApp.getInstance();
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        Context resCtx = AppLocaleHelper.localizedContext(this, cfg);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus st = repo != null ? repo.getCachedStatus() : null;

        if (cfg == null || !cfg.isConfigured()) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(resCtx.getString(R.string.qs_default_label));
            tile.setSubtitle(resCtx.getString(R.string.qs_setup_needed));
        } else if (st != null) {
            tile.setState(st.locked ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(cfg.getVehicleName());
            tile.setSubtitle(resCtx.getString(st.locked ? R.string.state_locked : R.string.state_unlocked)
                    + " • " + st.soc + "%");
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(cfg.getVehicleName());
            tile.setSubtitle(resCtx.getString(R.string.qs_tap_sync));
        }
        tile.updateTile();
    }
}
