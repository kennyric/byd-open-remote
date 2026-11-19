package com.bydwatch.companion.lab;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
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
import com.overdrive.app.byd.cloud.ChargeLimitValues;
import com.overdrive.app.byd.cloud.CloudCallTrace;
import com.google.android.material.switchmaterial.SwitchMaterial;

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
    private TextView tvLabActiveLimit;
    private TextView tvLabOptions;
    private AutoCompleteTextView acLabLimitValue;
    private TextView tvLabConsole;
    private ScrollView svLabConsole;
    private TextView tvLabVerdict;
    private SwitchMaterial switchLabReRead;
    private String lastKnownLimit;
    private LabRetryPolicy retryPolicy = LabRetryPolicy.defaults();
    private final LabConsole console = new LabConsole(200);

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
        tvLabActiveLimit = findViewById(R.id.tvLabActiveLimit);
        tvLabOptions = findViewById(R.id.tvLabOptions);
        acLabLimitValue = findViewById(R.id.acLabLimitValue);
        tvLabConsole = findViewById(R.id.tvLabConsole);
        svLabConsole = findViewById(R.id.svLabConsole);
        tvLabVerdict = findViewById(R.id.tvLabVerdict);
        switchLabReRead = findViewById(R.id.switchLabReRead);

        View readLimit = findViewById(R.id.btnLabReadLimit);
        if (readLimit != null) readLimit.setOnClickListener(v -> readLimit());
        View setLimit = findViewById(R.id.btnLabSetLimit);
        if (setLimit != null) setLimit.setOnClickListener(v -> confirmWrite());
        View copyAll = findViewById(R.id.btnLabCopyAll);
        if (copyAll != null) copyAll.setOnClickListener(v -> copyConsole());
        View share = findViewById(R.id.btnLabShare);
        if (share != null) share.setOnClickListener(v -> shareConsole());
        View clear = findViewById(R.id.btnLabClear);
        if (clear != null) clear.setOnClickListener(v -> clearConsole());

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

    private void readLimit() {
        if (client == null || repository == null) return;
        String typed = etLabEnergy != null ? etLabEnergy.getText().toString().trim() : "";
        final String energyType = typed.isEmpty() ? "0" : typed;
        final String vin = activeVin;
        repository.getExecutor().execute(() -> {
            BydCloudClient.AcCurrentLimitResult result =
                    client.readAcCurrentLimit(vin, energyType);
            runOnUiThread(() -> renderReadResult(result));
        });
    }

    private void renderReadResult(BydCloudClient.AcCurrentLimitResult result) {
        if (result == null || result.trace == null) return;
        CloudCallTrace trace = result.trace;
        if (result.limit != null) {
            lastKnownLimit = result.limit.active;
            String active = result.limit.active != null ? result.limit.active : "—";
            if (tvLabActiveLimit != null) tvLabActiveLimit.setText(active);
            if (tvLabOptions != null) {
                tvLabOptions.setText(result.limit.options.isEmpty()
                        ? "—" : TextUtils.join("  ", result.limit.options));
            }
            if (acLabLimitValue != null) {
                if (result.limit.active != null) acLabLimitValue.setText(result.limit.active);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_dropdown_item_1line, result.limit.options);
                acLabLimitValue.setAdapter(adapter);
            }
        } else if (tvLabActiveLimit != null) {
            String detail = trace.error != null ? trace.error
                    : (trace.code + (trace.message.isEmpty() ? "" : " " + trace.message));
            tvLabActiveLimit.setText(getString(R.string.lab_read_failed) + ": " + detail);
        }
        console.add(trace);
        renderConsole();
    }

    private void renderConsole() {
        if (tvLabConsole == null) return;
        String text = console.text();
        tvLabConsole.setText(text.isEmpty() ? getString(R.string.lab_console_empty) : text);
        if (svLabConsole != null) {
            svLabConsole.post(() -> svLabConsole.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyConsole() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        manager.setPrimaryClip(ClipData.newPlainText("BYD Lab", console.shareText()));
        Toast.makeText(this, R.string.lab_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareConsole() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, console.shareText());
        startActivity(Intent.createChooser(send, getString(R.string.lab_share_title)));
    }

    private void clearConsole() {
        console.clear();
        renderConsole();
    }

    @VisibleForTesting
    void setRetryPolicyForTesting(LabRetryPolicy policy) {
        if (policy != null) retryPolicy = policy;
    }

    private void confirmWrite() {
        if (client == null || acLabLimitValue == null) return;
        final String value = acLabLimitValue.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, R.string.lab_limit_value, Toast.LENGTH_SHORT).show();
            return;
        }
        String maskedVin = activeVin.length() > 4
                ? "***" + activeVin.substring(activeVin.length() - 4) : activeVin;
        new AlertDialog.Builder(this)
                .setTitle(R.string.lab_confirm_write_title)
                .setMessage(getString(R.string.lab_confirm_write_message)
                        + "\n\n{\"vin\":\"" + maskedVin + "\",\"limitCurrentValue\":\"" + value + "\"}")
                .setNegativeButton(R.string.lab_cancel, null)
                .setPositiveButton(R.string.lab_send_confirm, (dialog, which) -> performWrite(value))
                .show();
    }

    private void performWrite(String value) {
        final String vin = activeVin;
        final String energyType = etLabEnergy != null
                && !etLabEnergy.getText().toString().trim().isEmpty()
                ? etLabEnergy.getText().toString().trim() : "0";
        final boolean reRead = switchLabReRead == null || switchLabReRead.isChecked();
        final String knownBefore = lastKnownLimit;
        repository.getExecutor().execute(() -> {
            String before = knownBefore;
            if (reRead && before == null) {
                BydCloudClient.AcCurrentLimitResult beforeRead =
                        client.readAcCurrentLimit(vin, energyType);
                if (beforeRead.limit != null) before = beforeRead.limit.active;
                console.add(beforeRead.trace);
            }

            BydCloudClient.AcCurrentLimitResult write =
                    client.writeAcCurrentLimit(vin, value);
            String after = null;
            boolean reReadCompleted = false;

            if (write.trace.isSuccess() && reRead) {
                sleep(retryPolicy.delayMs);
                for (int attempt = 0; attempt < retryPolicy.attempts; attempt++) {
                    BydCloudClient.AcCurrentLimitResult read =
                            client.readAcCurrentLimit(vin, energyType);
                    console.add(read.trace);
                    if (read.limit != null) {
                        after = read.limit.active;
                        reReadCompleted = true;
                        if (ChargeLimitValues.same(after, value)) break;
                    }
                    if (attempt < retryPolicy.attempts - 1) sleep(retryPolicy.delayMs);
                }
            }

            final String beforeFinal = before;
            final String afterFinal = after;
            final boolean readFinal = reReadCompleted;
            final LabVerdict verdict = LabVerdict.classify(
                    beforeFinal, value, afterFinal,
                    write.trace.code, write.trace.error, readFinal);
            console.add(write.trace);
            runOnUiThread(() -> {
                if (tvLabVerdict != null) {
                    tvLabVerdict.setText(verdict.name()
                            + " · " + displayLimit(beforeFinal)
                            + " → " + displayLimit(afterFinal)
                            + " · " + write.trace.durationMs + " ms");
                }
                renderConsole();
            });
        });
    }

    private static void sleep(long delayMs) {
        if (delayMs <= 0) return;
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String displayLimit(String value) {
        return value == null || value.isEmpty() ? "—" : value;
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
