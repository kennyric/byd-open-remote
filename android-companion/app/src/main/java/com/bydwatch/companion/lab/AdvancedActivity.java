package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.VehicleStatusBroadcaster;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashMap;
import java.util.Map;

/** Advanced settings for the Overdrive WebView. */
public class AdvancedActivity extends AppCompatActivity {

    private SwitchMaterial switchShow;
    private SwitchMaterial switchHideUrl;
    private SwitchMaterial switchMicrogMaps;
    private RadioGroup rgControlsVisibility;
    private EditText etBase;
    private RadioGroup rgTop;
    private RadioGroup rgMore;
    private View layoutMore;
    private MaterialButton btnLoadAll;
    private EditText etManual;
    private TextView tvPreview;
    private TextView tvOverdriveStatus;
    private View btnOverdriveLogin;
    private View btnOverdriveRetry;
    private TextView tvAccessDetail;
    private EditText etAccessCode;
    private MaterialButton btnAccessConnect;
    private MaterialButton btnAccessDisconnect;
    private EditText etSessionTtl;
    private ConfigStore configStore;
    private int statusGeneration;

    private final Map<Integer, String> morePaths = new HashMap<>();
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (BuildConfig.DEMO_BUILD) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);

        switchShow = findViewById(R.id.switchAdvShowOverdrive);
        switchHideUrl = findViewById(R.id.switchAdvHideUrlBar);
        switchMicrogMaps = findViewById(R.id.switchAdvMicrogMaps);
        rgControlsVisibility = findViewById(R.id.rgAdvControlsVisibility);
        etBase = findViewById(R.id.etAdvBaseUrl);
        rgTop = findViewById(R.id.rgAdvTopPages);
        rgMore = findViewById(R.id.rgAdvMorePages);
        layoutMore = findViewById(R.id.layoutAdvMorePages);
        btnLoadAll = findViewById(R.id.btnAdvLoadAll);
        etManual = findViewById(R.id.etAdvManualUrl);
        tvPreview = findViewById(R.id.tvAdvPreview);
        tvOverdriveStatus = findViewById(R.id.tvAdvOverdriveStatus);
        btnOverdriveLogin = findViewById(R.id.btnAdvOverdriveLogin);
        btnOverdriveRetry = findViewById(R.id.btnAdvOverdriveRetry);
        if (btnOverdriveRetry != null) {
            btnOverdriveRetry.setOnClickListener(v -> refreshOverdriveStatus());
        }
        View guide = findViewById(R.id.btnAdvOverdriveGuide);
        if (guide != null) {
            guide.setOnClickListener(v -> openOverdriveGuide());
        }

        ImageButton back = findViewById(R.id.btnAdvBack);
        if (back != null) back.setOnClickListener(v -> finish());
        View reset = findViewById(R.id.btnAdvReset);
        if (reset != null) reset.setOnClickListener(v -> resetForm());
        View save = findViewById(R.id.btnAdvSave);
        if (save != null) save.setOnClickListener(v -> save());
        if (btnOverdriveLogin != null) {
            btnOverdriveLogin.setOnClickListener(v -> startActivity(
                    CarWebActivity.intent(this, "/", null)));
        }
        tvAccessDetail = findViewById(R.id.tvAdvAccessDetail);
        etAccessCode = findViewById(R.id.etAdvAccessCode);
        btnAccessConnect = findViewById(R.id.btnAdvAccessConnect);
        btnAccessDisconnect = findViewById(R.id.btnAdvAccessDisconnect);
        if (btnAccessConnect != null) {
            btnAccessConnect.setOnClickListener(v -> connectAccess());
        }
        if (btnAccessDisconnect != null) {
            btnAccessDisconnect.setOnClickListener(v -> disconnectAccess());
        }

        etSessionTtl = findViewById(R.id.etAdvSessionTtl);
        configStore = resolveConfigStore();

        populateMorePages();
        bindListeners();
        loadFromPrefs();
        updatePreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshOverdriveStatus();
    }

    private void refreshOverdriveStatus() {
        final String base = CarWebPrefs.baseUrl(this);
        final int generation = ++statusGeneration;
        if (tvOverdriveStatus != null) {
            tvOverdriveStatus.setText(R.string.advanced_overdrive_checking);
            tvOverdriveStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
        if (btnOverdriveLogin != null) btnOverdriveLogin.setVisibility(View.GONE);
        if (btnOverdriveRetry != null) btnOverdriveRetry.setVisibility(View.GONE);
        renderAccessDetail();

        final Context app = getApplicationContext();
        OverdriveWork.run(() -> {
            boolean reachable = !base.trim().isEmpty() && OverdriveClient.isReachable(base);
            boolean hasSession = reachable
                    && OverdriveSession.hasJwt(app, System.currentTimeMillis());
            runOnUiThread(() -> {
                if (generation != statusGeneration) return;
                renderOverdriveStatus(reachable, hasSession);
            });
        });
    }

    private void renderOverdriveStatus(boolean reachable, boolean hasSession) {
        if (tvOverdriveStatus == null) return;
        if (!reachable) {
            tvOverdriveStatus.setText(R.string.advanced_overdrive_unreachable);
            tvOverdriveStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose));
            if (btnOverdriveLogin != null) btnOverdriveLogin.setVisibility(View.GONE);
            if (btnOverdriveRetry != null) btnOverdriveRetry.setVisibility(View.VISIBLE);
            return;
        }
        if (hasSession) {
            tvOverdriveStatus.setText(R.string.advanced_overdrive_connected);
            tvOverdriveStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            if (btnOverdriveLogin != null) btnOverdriveLogin.setVisibility(View.GONE);
            if (btnOverdriveRetry != null) btnOverdriveRetry.setVisibility(View.GONE);
            return;
        }
        tvOverdriveStatus.setText(R.string.advanced_overdrive_login_needed);
        tvOverdriveStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
        if (btnOverdriveLogin != null) btnOverdriveLogin.setVisibility(View.VISIBLE);
        if (btnOverdriveRetry != null) btnOverdriveRetry.setVisibility(View.VISIBLE);
    }

    private void renderAccessDetail() {
        if (tvAccessDetail == null) return;
        String device = OverdriveSession.deviceId(this);
        if (device == null || device.isEmpty()) {
            tvAccessDetail.setText(R.string.advanced_access_detail_none);
        } else {
            tvAccessDetail.setText(getString(R.string.overdrive_access_device, device));
        }
        if (etAccessCode != null && etAccessCode.getText().length() == 0
                && OverdriveSession.hasAccessCode(this)) {
            etAccessCode.setHint(R.string.advanced_access_code_saved);
        }
        if (btnAccessDisconnect != null) {
            btnAccessDisconnect.setEnabled(OverdriveSession.hasAccessCode(this));
        }
        if (btnAccessConnect != null) {
            btnAccessConnect.setText(OverdriveSession.hasJwt(this, System.currentTimeMillis())
                    ? R.string.advanced_access_reconnect : R.string.advanced_access_connect);
        }
    }

    /** Exchange the typed access code for an Overdrive JWT. */
    private void connectAccess() {
        String code = etAccessCode != null ? etAccessCode.getText().toString().trim() : "";
        if (!OverdriveSession.isPlausibleAccessCode(code)) {
            Toast.makeText(this, R.string.advanced_access_code_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        final String base = CarWebPrefs.baseUrl(this);
        if (base.trim().isEmpty()) {
            Toast.makeText(this, R.string.advanced_invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }
        OverdriveSession.setAccessCode(this, code);
        if (btnAccessConnect != null) btnAccessConnect.setEnabled(false);
        if (tvAccessDetail != null) tvAccessDetail.setText(R.string.advanced_access_connecting);
        final int generation = ++statusGeneration;

        final Context app = getApplicationContext();
        OverdriveWork.run(() -> {
            String deviceId = OverdriveClient.deviceId(base);
            if (deviceId == null) {
                finishConnect(generation, false, R.string.advanced_overdrive_unreachable);
                return;
            }
            OverdriveSession.setDeviceId(app, deviceId);
            OverdriveClient.Outcome<String> outcome = OverdriveClient.login(base, deviceId, code);
            if (!outcome.ok() || outcome.value == null) {
                String message = outcome.error;
                runOnUiThread(() -> {
                    if (generation != statusGeneration) return;
                    if (btnAccessConnect != null) btnAccessConnect.setEnabled(true);
                    renderAccessDetail();
                    Toast.makeText(app, message != null ? message
                                    : getString(R.string.overdrive_controls_error_generic),
                            Toast.LENGTH_SHORT).show();
                });
                return;
            }
            OverdriveSession.setJwt(app, outcome.value, 30L * 24 * 60 * 60,
                    System.currentTimeMillis());
            finishConnect(generation, true, 0);
        });
    }

    private void finishConnect(int generation, boolean ok, int messageRes) {
        runOnUiThread(() -> {
            if (generation != statusGeneration) return;
            if (btnAccessConnect != null) btnAccessConnect.setEnabled(true);
            if (etAccessCode != null) etAccessCode.setText("");
            renderAccessDetail();
            if (messageRes != 0) {
                Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
            } else if (ok) {
                Toast.makeText(this, R.string.overdrive_access_connected, Toast.LENGTH_SHORT).show();
            }
            refreshOverdriveStatus();
        });
    }

    private void disconnectAccess() {
        OverdriveSession.clear(this);
        if (etAccessCode != null) {
            etAccessCode.setText("");
            etAccessCode.setHint(R.string.advanced_access_code_label);
        }
        Toast.makeText(this, R.string.overdrive_access_disconnected, Toast.LENGTH_SHORT).show();
        refreshOverdriveStatus();
    }

    private void populateMorePages() {
        for (OverdrivePages.Page page : OverdrivePages.morePages()) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(page.label);
            button.setTextColor(MaterialColors.getColor(button,
                    com.google.android.material.R.attr.colorOnSurface,
                    ContextCompat.getColor(this, R.color.text_primary)));
            rgMore.addView(button);
            morePaths.put(button.getId(), page.path);
        }
    }

    private void bindListeners() {
        if (btnLoadAll != null) {
            btnLoadAll.setOnClickListener(v -> {
                boolean show = layoutMore.getVisibility() != View.VISIBLE;
                layoutMore.setVisibility(show ? View.VISIBLE : View.GONE);
                btnLoadAll.setText(show ? R.string.advanced_show_top5 : R.string.advanced_load_all);
            });
        }

        rgTop.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || checkedId == -1) return;
            binding = true;
            rgMore.clearCheck();
            etManual.setText("");
            binding = false;
            updatePreview();
        });

        rgMore.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || checkedId == -1) return;
            binding = true;
            rgTop.clearCheck();
            etManual.setText("");
            binding = false;
            updatePreview();
        });

        etManual.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (binding) return;
                if (s != null && s.toString().trim().length() > 0) {
                    binding = true;
                    rgTop.clearCheck();
                    rgMore.clearCheck();
                    binding = false;
                }
                updatePreview();
            }
        });

        etBase.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updatePreview();
            }
        });
    }

    private void loadFromPrefs() {
        binding = true;
        switchShow.setChecked(CarWebPrefs.showOverdrive(this));
        switchHideUrl.setChecked(CarWebPrefs.hideUrlBar(this));
        if (switchMicrogMaps != null) {
            switchMicrogMaps.setChecked(configStore != null
                    && VehicleStatusBroadcaster.MICROG_TARGET_PACKAGE.equals(
                            configStore.getVehicleStatusTargetPackage()));
        }
        checkControlsVisibility(CarWebPrefs.overdriveControlsVisibility(this));
        if (etSessionTtl != null) {
            etSessionTtl.setText(String.valueOf(configStore != null
                    ? configStore.getSessionTtlMinutes()
                    : ConfigStore.DEFAULT_SESSION_TTL_MINUTES));
        }
        etBase.setText(CarWebPrefs.entryUrl(this));
        rgTop.clearCheck();
        rgMore.clearCheck();
        etManual.setText("");

        String page = CarWebPrefs.defaultPage(this);
        int topId = topIdForPath(page);
        if (topId != 0) {
            rgTop.check(topId);
        } else if (!"/".equals(page)) {
            Integer moreId = null;
            for (Map.Entry<Integer, String> entry : morePaths.entrySet()) {
                if (entry.getValue().equals(page)) {
                    moreId = entry.getKey();
                    break;
                }
            }
            if (moreId != null) {
                layoutMore.setVisibility(View.VISIBLE);
                btnLoadAll.setText(R.string.advanced_show_top5);
                rgMore.check(moreId);
            } else if (page != null && !page.isEmpty()) {
                etManual.setText(page);
            }
        }
        binding = false;
    }

    private void resetForm() {
        binding = true;
        switchShow.setChecked(false);
        switchHideUrl.setChecked(true);
        if (switchMicrogMaps != null) switchMicrogMaps.setChecked(false);
        checkControlsVisibility(CarWebPrefs.CONTROLS_HIDDEN);
        if (etSessionTtl != null) {
            etSessionTtl.setText(String.valueOf(ConfigStore.DEFAULT_SESSION_TTL_MINUTES));
        }
        etBase.setText("");
        rgTop.clearCheck();
        rgMore.clearCheck();
        etManual.setText("");
        rgTop.check(R.id.rbAdvVehicleControl);
        binding = false;
        updatePreview();
    }

    private void save() {
        String rawBase = etBase.getText().toString().trim();
        String base = CarWebUrl.origin(rawBase);
        if (!rawBase.isEmpty() && base.isEmpty()) {
            Toast.makeText(this, R.string.advanced_invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }
        String page = selectedPage();
        int ttlMinutes;
        try {
            ttlMinutes = Integer.parseInt(etSessionTtl != null
                    ? etSessionTtl.getText().toString().trim() : "");
        } catch (NumberFormatException invalid) {
            ttlMinutes = -1;
        }
        if (ttlMinutes < ConfigStore.MIN_SESSION_TTL_MINUTES
                || ttlMinutes > ConfigStore.MAX_SESSION_TTL_MINUTES) {
            Toast.makeText(this, R.string.advanced_session_ttl_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        CarWebPrefs.setShowOverdrive(this, switchShow.isChecked());
        CarWebPrefs.setHideUrlBar(this, switchHideUrl.isChecked());
        CarWebPrefs.setOverdriveControlsVisibility(this, selectedControlsVisibility());
        if (configStore != null) {
            configStore.setSessionTtlMinutes(ttlMinutes);
            configStore.setVehicleStatusTargetPackage(switchMicrogMaps != null
                    && switchMicrogMaps.isChecked()
                    ? VehicleStatusBroadcaster.MICROG_TARGET_PACKAGE
                    : VehicleStatusBroadcaster.DEFAULT_TARGET_PACKAGE);
        }
        CarWebPrefs.setBaseUrl(this, rawBase);
        CarWebPrefs.setDefaultPage(this, page);
        finish();
    }

    private void openOverdriveGuide() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.overdrive_guide_url))));
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, R.string.advanced_overdrive_guide_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private ConfigStore resolveConfigStore() {        try {
            BydWatchApp app = BydWatchApp.getInstance();
            return app != null ? app.getConfigStore() : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private void checkControlsVisibility(String visibility) {
        if (rgControlsVisibility == null) return;
        int id;
        if (CarWebPrefs.CONTROLS_ALWAYS.equals(visibility)) {
            id = R.id.rbAdvControlsAlways;
        } else if (CarWebPrefs.CONTROLS_CONNECTED.equals(visibility)) {
            id = R.id.rbAdvControlsConnected;
        } else {
            id = R.id.rbAdvControlsHidden;
        }
        rgControlsVisibility.check(id);
    }

    private String selectedControlsVisibility() {
        if (rgControlsVisibility == null) return CarWebPrefs.CONTROLS_HIDDEN;
        int id = rgControlsVisibility.getCheckedRadioButtonId();
        if (id == R.id.rbAdvControlsAlways) return CarWebPrefs.CONTROLS_ALWAYS;
        if (id == R.id.rbAdvControlsConnected) return CarWebPrefs.CONTROLS_CONNECTED;
        return CarWebPrefs.CONTROLS_HIDDEN;
    }

    private String selectedPage() {
        String manual = etManual.getText().toString().trim();
        if (!manual.isEmpty()) return manual;
        int topId = rgTop.getCheckedRadioButtonId();
        if (topId != 0) {
            String path = pathForTopId(topId);
            if (path != null) return path;
        }
        int moreId = rgMore.getCheckedRadioButtonId();
        if (moreId != 0) {
            String path = morePaths.get(moreId);
            if (path != null) return path;
        }
        return "/vehicle-control.html";
    }

    private void updatePreview() {
        if (tvPreview == null) return;
        String base = CarWebUrl.normalize(etBase.getText().toString());
        String resolved = CarWebUrl.entry(base, selectedPage());
        tvPreview.setText(resolved.isEmpty()
                ? getString(R.string.advanced_preview_empty) : resolved);
    }

    private int topIdForPath(String path) {
        if ("/vehicle-control.html".equals(path)) return R.id.rbAdvVehicleControl;
        if ("/events.html".equals(path)) return R.id.rbAdvEvents;
        if ("/charging.html".equals(path)) return R.id.rbAdvCharging;
        if ("/trips.html".equals(path)) return R.id.rbAdvTrips;
        if ("/live-view.html".equals(path)) return R.id.rbAdvLiveView;
        return 0;
    }

    private String pathForTopId(int id) {
        if (id == R.id.rbAdvVehicleControl) return "/vehicle-control.html";
        if (id == R.id.rbAdvEvents) return "/events.html";
        if (id == R.id.rbAdvCharging) return "/charging.html";
        if (id == R.id.rbAdvTrips) return "/trips.html";
        if (id == R.id.rbAdvLiveView) return "/live-view.html";
        return null;
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
