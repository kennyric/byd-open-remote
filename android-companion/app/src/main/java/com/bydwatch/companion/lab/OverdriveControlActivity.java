package com.bydwatch.companion.lab;

import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Native control of the car's AC charge current limit and charging schedule.
 *
 * <p>Every read and write goes to the car's Overdrive server; BYD Cloud does
 * not expose these writes. Overdrive is only the transport.
 */
public class OverdriveControlActivity extends AppCompatActivity {

    private enum State { CHECKING, CONNECTED, LOGIN_REQUIRED, UNREACHABLE }

    private MaterialButtonToggleGroup toggleCurrent;
    private MaterialButtonToggleGroup toggleRepeat;
    private MaterialSwitch switchEnabled;
    private MaterialButton btnStart;
    private MaterialButton btnSave;
    private MaterialButton btnStartWindow;
    private MaterialButton btnEndWindow;
    private MaterialButton btnLogin;
    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvCurrentHint;
    private TextView tvScheduleSource;

    private String startTime = "";
    private String endTime = "";
    private boolean binding;
    private int generation;

    public static Intent intent(Context context) {
        return new Intent(context, OverdriveControlActivity.class);
    }

    @VisibleForTesting
    public static void setExecutorForTesting(Executor executor) {
        OverdriveWork.setExecutorForTesting(executor);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overdrive_controls);

        toggleCurrent = findViewById(R.id.toggleCurrentLimit);
        toggleRepeat = findViewById(R.id.toggleScheduleRepeat);
        switchEnabled = findViewById(R.id.switchScheduleEnabled);
        btnStart = findViewById(R.id.btnStartCharging);
        btnSave = findViewById(R.id.btnScheduleSave);
        btnStartWindow = findViewById(R.id.btnScheduleStart);
        btnEndWindow = findViewById(R.id.btnScheduleEnd);
        btnLogin = findViewById(R.id.btnOverdriveControlLogin);
        tvStatus = findViewById(R.id.tvOverdriveControlStatus);
        tvDetail = findViewById(R.id.tvOverdriveControlDetail);
        tvCurrentHint = findViewById(R.id.tvCurrentLimitHint);
        tvScheduleSource = findViewById(R.id.tvScheduleSource);

        View back = findViewById(R.id.btnOverdriveControlsBack);
        if (back != null) back.setOnClickListener(v -> finish());
        View refresh = findViewById(R.id.btnOverdriveControlsRefresh);
        if (refresh != null) refresh.setOnClickListener(v -> refresh(true));
        if (btnLogin != null) {
            btnLogin.setOnClickListener(v -> startActivity(CarWebActivity.intent(this, "/", null)));
        }

        bindCurrentLimit();
        bindScheduleWindow();
        bindScheduleActions();
        refresh(false);
    }

    private void bindCurrentLimit() {
        toggleCurrent.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (binding || !isChecked) return;
            int state = stateForButton(checkedId);
            if (state == 0) return;
            writeCurrentLimit(state);
        });
    }

    private void bindScheduleWindow() {
        btnStartWindow.setOnClickListener(v ->
                pickTime(startTime, picked -> {
                    startTime = picked;
                    btnStartWindow.setText(picked);
                }));
        btnEndWindow.setOnClickListener(v ->
                pickTime(endTime, picked -> {
                    endTime = picked;
                    btnEndWindow.setText(picked);
                }));
    }

    private void bindScheduleActions() {
        switchEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            writeScheduleEnabled(isChecked);
        });
        btnSave.setOnClickListener(v -> writeSchedule());
        btnStart.setOnClickListener(v -> startCharging());
    }

    private void pickTime(String current, TimePicked callback) {
        int hour = 23;
        int minute = 0;
        if (current != null && current.length() == 5 && current.charAt(2) == ':') {
            try {
                hour = Integer.parseInt(current.substring(0, 2));
                minute = Integer.parseInt(current.substring(3));
            } catch (NumberFormatException ignored) {
                // Keep the default window.
            }
        }
        new TimePickerDialog(this, (view, h, m) ->
                callback.onPicked(String.format(Locale.ROOT, "%02d:%02d", h, m)),
                hour, minute, DateFormat.is24HourFormat(this)).show();
    }

    // ── Session and reads ──────────────────────────────────────────────

    private String base() {
        return CarWebPrefs.baseUrl(this);
    }

    private void refresh(boolean userInitiated) {
        final String base = base();
        final int run = ++generation;
        if (base.trim().isEmpty()) {
            render(State.UNREACHABLE);
            if (userInitiated) toast(R.string.overdrive_status_detail_unreachable);
            return;
        }
        render(State.CHECKING);

        final Context app = getApplicationContext();
        OverdriveWork.run(() -> {
            boolean reachable = OverdriveClient.isReachable(base);
            if (!reachable) {
                post(run, () -> render(State.UNREACHABLE));
                return;
            }
            String jwt = OverdriveSession.jwt(app);
            long now = System.currentTimeMillis();
            if (jwt == null) {
                String code = OverdriveSession.accessCode(app);
                if (code == null) {
                    post(run, () -> render(State.LOGIN_REQUIRED));
                    return;
                }
                String deviceId = OverdriveSession.deviceId(app);
                if (deviceId == null) {
                    deviceId = OverdriveClient.deviceId(base);
                    if (deviceId != null) OverdriveSession.setDeviceId(app, deviceId);
                }
                if (deviceId == null) {
                    post(run, () -> render(State.LOGIN_REQUIRED));
                    return;
                }
                OverdriveClient.Outcome<String> login =
                        OverdriveClient.login(base, deviceId, code);
                if (!login.ok() || login.value == null) {
                    post(run, () -> render(State.LOGIN_REQUIRED));
                    return;
                }
                OverdriveSession.setJwt(app, login.value, 30L * 24 * 60 * 60, now);
                jwt = login.value;
            }

            OverdriveClient.Outcome<OverdriveApi.CurrentLimit> current =
                    OverdriveClient.currentLimit(base, jwt);
            if (current.authFailed()) {
                OverdriveSession.clearJwt(app);
                post(run, () -> render(State.LOGIN_REQUIRED));
                return;
            }
            OverdriveClient.Outcome<OverdriveApi.Schedule> schedule =
                    OverdriveClient.schedule(base, jwt);
            if (schedule.authFailed()) {
                OverdriveSession.clearJwt(app);
                post(run, () -> render(State.LOGIN_REQUIRED));
                return;
            }
            post(run, () -> {
                render(State.CONNECTED);
                applyCurrentLimit(current.ok() ? current.value : null);
                applySchedule(schedule.ok() ? schedule.value : null);
                if (!current.ok() && current.error != null) showDetail(current.error);
            });
        });
    }

    private void post(int run, Runnable action) {
        runOnUiThread(() -> {
            if (run != generation) return;
            action.run();
        });
    }

    private void applyCurrentLimit(OverdriveApi.CurrentLimit limit) {
        binding = true;
        try {
            boolean adjustable = limit != null && limit.supported && limit.state > 0;
            toggleCurrent.setEnabled(adjustable);
            toggleCurrent.clearChecked();
            if (limit != null && limit.state > 0) {
                toggleCurrent.check(buttonForState(limit.state));
            }
            tvCurrentHint.setText(limit != null && !limit.supported
                    ? R.string.overdrive_current_unsupported
                    : R.string.overdrive_current_hint);
        } finally {
            binding = false;
        }
    }

    private void applySchedule(OverdriveApi.Schedule schedule) {
        binding = true;
        try {
            if (schedule == null || !schedule.supported) {
                switchEnabled.setEnabled(false);
                btnSave.setEnabled(false);
                tvScheduleSource.setText(R.string.overdrive_schedule_unsupported);
                return;
            }
            switchEnabled.setEnabled(true);
            btnSave.setEnabled(true);
            switchEnabled.setChecked(schedule.enabled);
            startTime = schedule.start != null ? schedule.start : "";
            endTime = schedule.end != null ? schedule.end : "";
            btnStartWindow.setText(startTime.isEmpty()
                    ? getString(R.string.overdrive_schedule_start_empty) : startTime);
            btnEndWindow.setText(endTime.isEmpty()
                    ? getString(R.string.overdrive_schedule_end_empty) : schedule.endLabel());
            OverdriveApi.Repeat repeat = schedule.repeat != null
                    ? schedule.repeat : OverdriveApi.Repeat.DAILY;
            toggleRepeat.check(buttonForRepeat(repeat));
            String readAt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                    .format(new Date(System.currentTimeMillis()));
            tvScheduleSource.setText(getString(R.string.overdrive_schedule_source,
                    readAt, schedule.source != null ? schedule.source : "Overdrive"));
        } finally {
            binding = false;
        }
    }

    // ── Writes ─────────────────────────────────────────────────────────

    private void writeCurrentLimit(int state) {
        final String base = base();
        final String jwt = OverdriveSession.jwt(this);
        if (jwt == null) {
            render(State.LOGIN_REQUIRED);
            return;
        }
        final int run = generation;
        setControlsEnabled(false);
        OverdriveWork.run(() -> {
            OverdriveClient.Outcome<OverdriveApi.CurrentLimit> outcome =
                    OverdriveClient.setCurrentLimit(base, jwt, state);
            post(run, () -> {
                setControlsEnabled(true);
                if (outcome.authFailed()) {
                    OverdriveSession.clearJwt(getApplicationContext());
                    render(State.LOGIN_REQUIRED);
                    return;
                }
                if (!outcome.ok()) {
                    toast(outcome.error != null ? outcome.error
                            : getString(R.string.overdrive_controls_error_generic));
                    refresh(false);
                    return;
                }
                applyCurrentLimit(outcome.value);
                if (outcome.value != null && outcome.value.label != null) {
                    toast(getString(R.string.overdrive_current_applied, outcome.value.label));
                }
            });
        });
    }

    private void writeSchedule() {
        final String base = base();
        final String jwt = OverdriveSession.jwt(this);
        if (jwt == null) {
            render(State.LOGIN_REQUIRED);
            return;
        }
        if (startTime.isEmpty() || endTime.isEmpty()) {
            toast(R.string.overdrive_schedule_start_empty);
            return;
        }
        final OverdriveApi.Repeat repeat = repeatForButton(toggleRepeat.getCheckedButtonId());
        final boolean enabled = switchEnabled.isChecked();
        final int run = generation;
        setControlsEnabled(false);
        OverdriveWork.run(() -> {
            OverdriveClient.Outcome<OverdriveApi.Schedule> outcome =
                    OverdriveClient.setSchedule(base, jwt, startTime, endTime, repeat, enabled);
            post(run, () -> {
                setControlsEnabled(true);
                if (outcome.authFailed()) {
                    OverdriveSession.clearJwt(getApplicationContext());
                    render(State.LOGIN_REQUIRED);
                    return;
                }
                if (!outcome.ok()) {
                    toast(outcome.error != null ? outcome.error
                            : getString(R.string.overdrive_controls_error_generic));
                    refresh(false);
                    return;
                }
                toast(R.string.overdrive_schedule_saved);
                // The POST response carries no "supported" flag, so re-read the
                // car's authoritative state instead of disabling the editor.
                refresh(false);
            });
        });
    }

    private void writeScheduleEnabled(boolean enabled) {
        final String base = base();
        final String jwt = OverdriveSession.jwt(this);
        if (jwt == null) {
            render(State.LOGIN_REQUIRED);
            return;
        }
        final int run = generation;
        OverdriveWork.run(() -> {
            OverdriveClient.Outcome<OverdriveApi.Schedule> outcome =
                    OverdriveClient.setScheduleEnabled(base, jwt, enabled);
            post(run, () -> {
                if (outcome.authFailed()) {
                    OverdriveSession.clearJwt(getApplicationContext());
                    render(State.LOGIN_REQUIRED);
                    return;
                }
                if (!outcome.ok()) {
                    toast(outcome.error != null ? outcome.error
                            : getString(R.string.overdrive_schedule_toggle_failed));
                    binding = true;
                    switchEnabled.setChecked(!enabled);
                    binding = false;
                }
            });
        });
    }

    private void startCharging() {
        final String base = base();
        final String jwt = OverdriveSession.jwt(this);
        if (jwt == null) {
            render(State.LOGIN_REQUIRED);
            return;
        }
        final int run = generation;
        btnStart.setEnabled(false);
        OverdriveWork.run(() -> {
            OverdriveClient.Outcome<String> outcome = OverdriveClient.startCharging(base, jwt);
            post(run, () -> {
                btnStart.setEnabled(true);
                if (outcome.authFailed()) {
                    OverdriveSession.clearJwt(getApplicationContext());
                    render(State.LOGIN_REQUIRED);
                    return;
                }
                toast(outcome.ok()
                        ? getString(R.string.overdrive_start_charging_done)
                        : (outcome.error != null ? outcome.error
                        : getString(R.string.overdrive_controls_error_generic)));
            });
        });
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private void render(State state) {
        if (tvStatus == null) return;
        int color;
        switch (state) {
            case CONNECTED:
                tvStatus.setText(R.string.overdrive_status_connected);
                tvDetail.setText(R.string.overdrive_status_detail_connected);
                color = R.color.accent_emerald;
                break;
            case LOGIN_REQUIRED:
                tvStatus.setText(R.string.overdrive_status_login_needed);
                tvDetail.setText(R.string.overdrive_status_detail_login_needed);
                color = R.color.accent_amber;
                break;
            case UNREACHABLE:
                tvStatus.setText(R.string.overdrive_status_unreachable);
                tvDetail.setText(R.string.overdrive_status_detail_unreachable);
                color = R.color.accent_rose;
                break;
            case CHECKING:
            default:
                tvStatus.setText(R.string.overdrive_status_checking);
                tvDetail.setText(R.string.overdrive_controls_detail_loading);
                color = R.color.accent_emerald;
                break;
        }
        tvStatus.setTextColor(ContextCompat.getColor(this, color));
        if (btnLogin != null) {
            btnLogin.setVisibility(state == State.LOGIN_REQUIRED ? View.VISIBLE : View.GONE);
        }
        setControlsEnabled(state == State.CONNECTED);
    }

    private void setControlsEnabled(boolean enabled) {
        toggleCurrent.setEnabled(enabled);
        switchEnabled.setEnabled(enabled);
        btnSave.setEnabled(enabled);
        btnStartWindow.setEnabled(enabled);
        btnEndWindow.setEnabled(enabled);
        toggleRepeat.setEnabled(enabled);
        btnStart.setEnabled(enabled);
    }

    private void showDetail(String message) {
        if (tvDetail != null) tvDetail.setText(message);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ── Id mapping ─────────────────────────────────────────────────────

    private static int buttonForState(int state) {
        switch (state) {
            case OverdriveApi.STATE_6A:
                return R.id.btnCurrent6;
            case OverdriveApi.STATE_8A:
                return R.id.btnCurrent8;
            case OverdriveApi.STATE_10A:
                return R.id.btnCurrent10;
            case OverdriveApi.STATE_16A:
                return R.id.btnCurrent16;
            case OverdriveApi.STATE_MAX:
                return R.id.btnCurrentMax;
            default:
                return View.NO_ID;
        }
    }

    private static int stateForButton(int id) {
        if (id == R.id.btnCurrent6) return OverdriveApi.STATE_6A;
        if (id == R.id.btnCurrent8) return OverdriveApi.STATE_8A;
        if (id == R.id.btnCurrent10) return OverdriveApi.STATE_10A;
        if (id == R.id.btnCurrent16) return OverdriveApi.STATE_16A;
        if (id == R.id.btnCurrentMax) return OverdriveApi.STATE_MAX;
        return 0;
    }

    private static int buttonForRepeat(OverdriveApi.Repeat repeat) {
        switch (repeat) {
            case ONCE:
                return R.id.btnRepeatOnce;
            case WEEKDAYS:
                return R.id.btnRepeatWeekdays;
            case DAILY:
            default:
                return R.id.btnRepeatDaily;
        }
    }

    private static OverdriveApi.Repeat repeatForButton(int id) {
        if (id == R.id.btnRepeatOnce) return OverdriveApi.Repeat.ONCE;
        if (id == R.id.btnRepeatWeekdays) return OverdriveApi.Repeat.WEEKDAYS;
        return OverdriveApi.Repeat.DAILY;
    }

    private interface TimePicked {
        void onPicked(String time);
    }
}
