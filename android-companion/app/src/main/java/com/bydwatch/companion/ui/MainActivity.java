package com.bydwatch.companion.ui;

import android.os.Bundle;
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

    private void saveCredentials() {
        String u = etUsername.getText().toString().trim();
        String p = etPassword.getText().toString().trim();
        String pin = etPin.getText().toString().trim();
        String vin = etVin.getText().toString().trim();

        if (configStore != null) {
            configStore.setUsername(u);
            configStore.setPassword(p);
            configStore.setPin(pin);
            configStore.setVin(vin);
            configStore.setRegion(SecureConfigStore.DEFAULT_REGION);
            configStore.setDefaultTempC(targetTempC);
        }

        logMessage("Credentials saved successfully.");
    }

    private void fetchVinAndTestLogin() {
        saveCredentials();
        logMessage("Logging in and fetching VIN...");
        BydWatchApp app = (BydWatchApp) getApplication();
        if (app == null) {
            app = BydWatchApp.getInstance();
        }

        app.getAsyncExecutor().execute(() -> {
            try {
                gateway.login();
                String vin = gateway.fetchFirstVin();
                if (vin != null && !vin.trim().isEmpty()) {
                    if (configStore != null) {
                        configStore.setVin(vin);
                    }
                    runOnUiThread(() -> {
                        etVin.setText(vin);
                        logMessage("Login successful. Found VIN: " + vin);
                    });
                    executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
                } else {
                    runOnUiThread(() -> logMessage("Login successful, but no vehicles found."));
                }
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> logMessage("Login & Fetch VIN failed: " + err));
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
        } catch (JSONException ignored) {}

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
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            long millis = status.ts > 1_000_000_000_000L ? status.ts : status.ts * 1000L;
            tvLastUpdated.setText(sdf.format(new Date(millis)));
        } else {
            tvLastUpdated.setText("Now");
        }
    }

    private void logMessage(String text) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        String time = sdf.format(new Date());
        String entry = "[" + time + "] " + text + "\n";
        tvStatusLog.append(entry);
    }
}
