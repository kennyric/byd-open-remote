package com.bydwatch.companion.lab;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.BydCloudClient;

import java.util.List;

/**
 * Debug-only trial screen for reading and writing the BYD AC charging current
 * limit. Task 5 supplies the shell and vehicle context; later tasks add the
 * call cards.
 */
public class LabActivity extends AppCompatActivity {

    private BydRepository repository;
    private BydCloudClient client;
    private ConfigStore configStore;
    private String activeVin = "";
    private boolean fullVinVisible;

    private TextView tvLabVin;
    private TextView tvLabEnergyRaw;
    private EditText etLabEnergy;
    private TextView tvLabRegion;
    private TextView tvLabSession;
    private TextView tvLabChargeState;
    private TextView tvLabPinState;
    private TextView tvLabUnavailable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (!LabGate.isEnabled(BuildConfig.DEBUG, BuildConfig.DEMO_BUILD)) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lab);
        bindViews();

        BydWatchApp app = (BydWatchApp) getApplication();
        configStore = app.getConfigStore();
        repository = app.getRepository();
        activeVin = configStore.getVin() != null ? configStore.getVin().trim() : "";

        BydGateway gateway = repository.getGateway();
        if (!(gateway instanceof CloudBydGateway) || activeVin.isEmpty()) {
            showUnavailable();
            return;
        }
        client = ((CloudBydGateway) gateway).getClient();
        if (client == null) {
            showUnavailable();
            return;
        }
        renderAll();
        refreshContext();
    }

    private void bindViews() {
        tvLabVin = findViewById(R.id.tvLabVin);
        tvLabEnergyRaw = findViewById(R.id.tvLabEnergyRaw);
        etLabEnergy = findViewById(R.id.etLabEnergy);
        tvLabRegion = findViewById(R.id.tvLabRegion);
        tvLabSession = findViewById(R.id.tvLabSession);
        tvLabChargeState = findViewById(R.id.tvLabChargeState);
        tvLabPinState = findViewById(R.id.tvLabPinState);
        tvLabUnavailable = findViewById(R.id.tvLabUnavailable);

        ImageButton back = findViewById(R.id.btnLabBack);
        if (back != null) back.setOnClickListener(v -> finish());

        View refresh = findViewById(R.id.btnLabRefreshContext);
        if (refresh != null) refresh.setOnClickListener(v -> refreshContext());

        if (tvLabVin != null) {
            tvLabVin.setOnLongClickListener(v -> {
                fullVinVisible = !fullVinVisible;
                renderVin();
                return true;
            });
        }
    }

    private void showUnavailable() {
        if (tvLabUnavailable != null) tvLabUnavailable.setVisibility(View.VISIBLE);
        if (tvLabVin != null) tvLabVin.setText(R.string.lab_unavailable);
    }

    private void renderAll() {
        renderVin();
        if (tvLabRegion != null) tvLabRegion.setText(configStore.getRegion());
        if (tvLabSession != null) {
            tvLabSession.setText(configStore.isConfigured()
                    ? R.string.lab_session_configured : R.string.lab_session_unconfigured);
        }
        if (tvLabPinState != null) {
            tvLabPinState.setText(client != null && client.isControlPasswordVerified()
                    ? R.string.lab_pin_verified : R.string.lab_pin_unverified);
        }
        renderChargeState();
    }

    private void renderVin() {
        if (tvLabVin == null) return;
        String vin = activeVin;
        if (!fullVinVisible && vin.length() > 4) {
            vin = "***" + vin.substring(vin.length() - 4);
        }
        tvLabVin.setText(vin);
    }

    private void renderChargeState() {
        if (tvLabChargeState == null) return;
        VehicleStatus status = repository != null ? repository.getCachedStatus() : null;
        if (status == null) {
            tvLabChargeState.setText("—");
            return;
        }
        String state;
        if (status.chargingState == VehicleStatus.CHARGING_ACTIVE) {
            state = getString(R.string.lab_charge_active);
        } else if (status.chargingState == VehicleStatus.CHARGING_CONNECTED) {
            state = getString(R.string.lab_charge_connected);
        } else {
            state = getString(R.string.lab_charge_none);
        }
        if (status.chargePowerKw != null && status.chargePowerKw > 0) {
            state = state + " · " + String.format(java.util.Locale.US, "%.1f kW", status.chargePowerKw);
        }
        tvLabChargeState.setText(state);
    }

    private void refreshContext() {
        if (client == null || configStore == null || repository == null) return;
        final String vin = activeVin;
        repository.getExecutor().execute(() -> {
            List<VehicleSummary> vehicles = configStore.getVehicles();
            String raw = LabContext.rawEnergyType(vin, vehicles);
            String fallback = "0";
            if (!"0".equals(raw) && !"2".equals(raw)) {
                try {
                    String[] pair = client.fetchFirstVinAndEnergyType();
                    if (pair != null && pair.length > 1 && pair[1] != null) {
                        fallback = pair[1].trim();
                    }
                } catch (Exception ignored) {
                }
            }
            final String rawFinal = raw;
            final String resolved = LabContext.resolveEnergyType(vin, vehicles, fallback);
            runOnUiThread(() -> {
                if (tvLabEnergyRaw != null) {
                    tvLabEnergyRaw.setText(rawFinal.isEmpty()
                            ? getString(R.string.lab_energy_unknown) : rawFinal);
                }
                if (etLabEnergy != null && etLabEnergy.getText().toString().trim().isEmpty()) {
                    etLabEnergy.setText(resolved);
                }
                renderChargeState();
            });
        });
    }
}
