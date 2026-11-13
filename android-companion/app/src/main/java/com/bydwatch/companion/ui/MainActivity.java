package com.bydwatch.companion.ui;

import android.os.Bundle;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.SecureConfigStore;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Standalone Android Activity for BYD login, VIN fetch, live telemetry, and vehicle control.
 */
public class MainActivity extends AppCompatActivity {

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private ConfigStore configStore;
    private BydGateway gateway;
    private BydRepository repository;

    private int targetTempC = WatchProtocol.DEFAULT_TEMP_C;

    // UI references
    private TextView tvAuthNotice;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etPin;
    private EditText etVin;
    private Button btnSaveCredentials;
    private Button btnFetchVin;

    private TextView tvSoc;
    private TextView tvRange;
    private TextView tvLockState;
    private TextView tvCabinTemp;
    private TextView tvAcState;
    private TextView tvLastUpdated;

    private Button btnRefresh;
    private Button btnLock;
    private Button btnUnlock;
    private Button btnTempMinus;
    private TextView tvTargetTemp;
    private Button btnTempPlus;
    private Button btnAcOn;
    private Button btnAcOff;
    private Button btnLights;
    private Button btnHorn;

    private TextView tvWatchStatus;
    private TextView tvStatusLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BydWatchApp app = (BydWatchApp) getApplication();
        if (app == null) {
            app = BydWatchApp.getInstance();
        }
        configStore = app.getConfigStore();
        gateway = app.getGateway();
        repository = app.getRepository();

        bindViews();
        initValues();
        setupListeners();
    }

    private void bindViews() {
        tvAuthNotice = findViewById(R.id.tvAuthNotice);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etPin = findViewById(R.id.etPin);
        etVin = findViewById(R.id.etVin);
        btnSaveCredentials = findViewById(R.id.btnSaveCredentials);
        btnFetchVin = findViewById(R.id.btnFetchVin);

        tvSoc = findViewById(R.id.tvSoc);
        tvRange = findViewById(R.id.tvRange);
        tvLockState = findViewById(R.id.tvLockState);
        tvCabinTemp = findViewById(R.id.tvCabinTemp);
        tvAcState = findViewById(R.id.tvAcState);
        tvLastUpdated = findViewById(R.id.tvLastUpdated);

        btnRefresh = findViewById(R.id.btnRefresh);
        btnLock = findViewById(R.id.btnLock);
        btnUnlock = findViewById(R.id.btnUnlock);
        btnTempMinus = findViewById(R.id.btnTempMinus);
        tvTargetTemp = findViewById(R.id.tvTargetTemp);
        btnTempPlus = findViewById(R.id.btnTempPlus);
        btnAcOn = findViewById(R.id.btnAcOn);
        btnAcOff = findViewById(R.id.btnAcOff);
        btnLights = findViewById(R.id.btnLights);
        btnHorn = findViewById(R.id.btnHorn);

        tvWatchStatus = findViewById(R.id.tvWatchStatus);
        tvStatusLog = findViewById(R.id.tvStatusLog);
        tvStatusLog.setMovementMethod(new ScrollingMovementMethod());
    }

    private void initValues() {
        if (configStore != null) {
            String u = configStore.getUsername();
            if (u != null) etUsername.setText(u);

            String p = configStore.getPassword();
            if (p != null) etPassword.setText(p);

            String pin = configStore.getPin();
            if (pin != null) etPin.setText(pin);

            String vin = configStore.getVin();
            if (vin != null) etVin.setText(vin);

            targetTempC = configStore.getDefaultTempC();
            if (targetTempC < WatchProtocol.MIN_TEMP_C || targetTempC > WatchProtocol.MAX_TEMP_C) {
                targetTempC = WatchProtocol.DEFAULT_TEMP_C;
            }
        }
        tvTargetTemp.setText(targetTempC + "°C");

        if (repository != null && repository.getCachedStatus() != null) {
            updateTelemetryUi(repository.getCachedStatus());
        }

        updateWatchStatus();
    }

    private void updateWatchStatus() {
        if (tvWatchStatus != null) {
            if (configStore != null && configStore.isConfigured()) {
                tvWatchStatus.setText(R.string.watch_status_ready);
            } else {
                tvWatchStatus.setText(R.string.watch_status_unconfigured);
            }
        }
    }

    private void setupListeners() {
        btnSaveCredentials.setOnClickListener(v -> saveCredentials());
        btnFetchVin.setOnClickListener(v -> fetchVinAndTestLogin());

        btnTempMinus.setOnClickListener(v -> {
            if (targetTempC > WatchProtocol.MIN_TEMP_C) {
                targetTempC--;
                tvTargetTemp.setText(targetTempC + "°C");
                if (configStore != null) {
                    configStore.setDefaultTempC(targetTempC);
                }
            }
        });

        btnTempPlus.setOnClickListener(v -> {
            if (targetTempC < WatchProtocol.MAX_TEMP_C) {
                targetTempC++;
                tvTargetTemp.setText(targetTempC + "°C");
                if (configStore != null) {
                    configStore.setDefaultTempC(targetTempC);
                }
            }
        });

        btnRefresh.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_STATUS, -1));
        btnLock.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_LOCK, -1));
        btnUnlock.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_UNLOCK, -1));
        btnAcOn.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_AC_ON, targetTempC));
        btnAcOff.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_AC_OFF, -1));
        btnLights.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_LIGHTS, -1));
        btnHorn.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_HORN, -1));
    }

    private boolean validateInputs(boolean requireVin) {
        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString().trim();
        String pin = etPin.getText().toString().trim();
        String vin = etVin.getText().toString().trim();

        if (u.isEmpty()) {
            logMessage("Validation error: Username/email cannot be empty.");
            return false;
        }
        if (p.isEmpty()) {
            logMessage("Validation error: Password cannot be empty.");
            return false;
        }
        if (pin.isEmpty() || !pin.matches("\\d{6}")) {
            logMessage("Validation error: Control PIN must be exactly 6 numeric digits.");
            return false;
        }
        if (requireVin && vin.isEmpty()) {
            logMessage("Validation error: VIN cannot be empty.");
            return false;
        }
        return true;
    }

    private void saveCredentials() {
        if (!validateInputs(false)) {
            return;
        }

        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString().trim();
        String pin = etPin.getText().toString().trim();
        String vin = etVin.getText().toString().trim();

        if (configStore != null) {
            configStore.setUsername(u);
            configStore.setPassword(p);
            configStore.setPin(pin);
            if (!vin.isEmpty()) {
                configStore.setVin(vin);
            }
            configStore.setRegion(SecureConfigStore.DEFAULT_REGION);
            configStore.setDefaultTempC(targetTempC);
        }

        updateWatchStatus();
        logMessage("Credentials saved successfully.");
    }

    private void fetchVinAndTestLogin() {
        if (!validateInputs(false)) {
            return;
        }

        // Persist entered credentials first so repository can use them
        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString().trim();
        String pin = etPin.getText().toString().trim();
        String vin = etVin.getText().toString().trim();

        if (configStore != null) {
            configStore.setUsername(u);
            configStore.setPassword(p);
            configStore.setPin(pin);
            if (!vin.isEmpty()) {
                configStore.setVin(vin);
            }
            configStore.setRegion(SecureConfigStore.DEFAULT_REGION);
            configStore.setDefaultTempC(targetTempC);
        }

        btnFetchVin.setEnabled(false);
        logMessage("Logging in and fetching VIN...");

        repository.fetchFirstVin(new BydRepository.VinCallback() {
            @Override
            public void onSuccess(String fetchedVin) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    etVin.setText(fetchedVin);
                    logMessage("Login successful. Found VIN: " + fetchedVin);
                    updateWatchStatus();
                    executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    logMessage("Login & Fetch VIN failed: " + error);
                });
            }
        });
    }

    private void executeWatchAction(String action, int tempC) {
        if (configStore != null && !configStore.isConfigured()) {
            logMessage(action + " failed: BYD credentials are not configured.");
            return;
        }

        String cmdId = "ui_" + System.currentTimeMillis();
        JSONObject json = new JSONObject();
        try {
            json.put("id", cmdId);
            json.put("action", action);
            if (WatchProtocol.ACTION_AC_ON.equals(action)) {
                json.put("tempC", tempC >= WatchProtocol.MIN_TEMP_C ? tempC : targetTempC);
            }
        } catch (JSONException e) {
            logMessage("Failed to build command payload for " + action + ": " + e.getMessage());
            return;
        }

        logMessage("Sending " + action + "...");
        repository.handleWatchJson(json.toString(), responseJson -> {
            runOnUiThread(() -> handleWatchResponse(responseJson));
        });
    }

    private void handleWatchResponse(String responseJson) {
        try {
            WatchResponse resp = WatchResponse.fromJson(new JSONObject(responseJson));
            if (resp != null) {
                if (resp.ok) {
                    logMessage(resp.action + ": OK (" + (resp.msg.isEmpty() ? "Success" : resp.msg) + ")");
                    if (resp.state != null) {
                        updateTelemetryUi(resp.state);
                    }
                } else {
                    logMessage(resp.action + " failed [" + resp.err + "]: " + resp.msg);
                }
            } else {
                logMessage("Received null response payload.");
            }
        } catch (Exception e) {
            logMessage("Error processing response: " + e.getMessage());
        }
    }

    private void updateTelemetryUi(VehicleStatus status) {
        if (status == null) return;
        tvSoc.setText(status.soc + "%");
        tvRange.setText(status.rangeKm + " km");
        tvLockState.setText(status.locked ? "Locked" : "Unlocked");
        if (status.cabinTempC != null) {
            tvCabinTemp.setText(status.cabinTempC + "°C");
        } else {
            tvCabinTemp.setText("--°C");
        }
        tvAcState.setText(status.acOn ? "AC On" : "AC Off");
        if (status.ts > 0) {
            long millis = status.ts > 1_000_000_000_000L ? status.ts : status.ts * 1000L;
            tvLastUpdated.setText(timeFormat.format(new Date(millis)));
        } else {
            tvLastUpdated.setText("Now");
        }
    }

    private void logMessage(String text) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> logMessage(text));
            return;
        }
        String time = timeFormat.format(new Date());
        String entry = "[" + time + "] " + text + "\n";
        tvStatusLog.append(entry);
        trimLogIfNeeded();
    }

    private void trimLogIfNeeded() {
        CharSequence current = tvStatusLog.getText();
        if (current != null && current.length() > 5000) {
            String s = current.toString();
            int cutIndex = s.indexOf('\n', s.length() - 4000);
            if (cutIndex >= 0) {
                tvStatusLog.setText(s.substring(cutIndex + 1));
            }
        }
    }
}
