package com.bydwatch.companion.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.NestedScrollView;
import androidx.drawerlayout.widget.DrawerLayout;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.SecureConfigStore;
import com.bydwatch.companion.model.RegionOption;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;
import com.bydwatch.companion.wear.WearBridgeService;
import com.bydwatch.companion.wear.WearEngineBridge;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Android Activity for BYD login, VIN fetch, live telemetry, and vehicle control.
 */
public class MainActivity extends AppCompatActivity {

    private static final AtomicLong CMD_SEQ = new AtomicLong();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private ConfigStore configStore;
    private BydGateway gateway;
    private BydRepository repository;
    private WearEngineBridge bridge;
    private WearEngineBridge.StateListener stateListener;
    private WearBridgeService.ServiceStateListener serviceStateListener;

    private static final int RC_PERMISSIONS = 1001;
    private static final long CLIMATE_DEBOUNCE_MS = 700L;
    private static final String STATE_CURRENT_PAGE_ID = "state_current_page_id";

    private int restoredPageId = 0;

    private int targetTempC = WatchProtocol.DEFAULT_TEMP_C;
    private int targetTempGear = ConfigStore.DEFAULT_TEMP_GEAR;
    private int targetFanLevel = ConfigStore.DEFAULT_FAN_LEVEL;
    private int targetWindMode = ConfigStore.DEFAULT_WIND_MODE;
    private boolean updatingManualAcSwitch = false;
    private boolean syncIntervalUnitHours = false;
    private VehicleStatus lastStatus;
    private final Handler climateDebounceHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingClimateSend = () -> {
        if (!isFinishing() && !isDestroyed()) {
            sendCurrentAcOnCommand();
        }
    };

    // UI references
    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private ImageButton btnHamburger;
    private TextView tvDrawerCarModel;
    private TextView tvDrawerAccount;
    private int currentPageId = R.id.nav_controls;

    private NestedScrollView rootScroll;
    private TextView tvHeaderTitle;
    private TextView tvHeaderSubtitle;
    private View cardControls;
    private View cardAccount;
    private View cardPreferences;
    private View cardBridge;
    private View layoutAccountFields;
    private View layoutSetupGuideHeader;
    private View layoutSetupGuideSteps;
    private Button btnToggleSetupGuide;
    private TextView tvAccountSummary;
    private Button btnToggleAccount;
    private TextView tvAuthNotice;
    private AutoCompleteTextView actvRegion;
    private EditText etCountryCode;
    private TextInputLayout tilPassword;
    private TextInputLayout tilPin;
    private EditText etUsername;
    private EditText etPassword;
    private EditText etPin;
    private EditText etVin;
    private Button btnSaveCredentials;
    private Button btnFetchVin;

    private TextView tvControlsTitle;
    private TextView tvSoc;
    private ProgressBar pbBatterySoc;
    private TextView tvRange;
    private TextView tvChargingBadge;
    private TextView tvOpeningBadge;
    private View layoutCabinTemp;
    private TextView tvLockState;
    private TextView tvCabinTemp;
    private TextView tvAcState;
    private TextView tvLastUpdated;

    private Button btnRefresh;
    private Button btnLock;
    private Button btnUnlock;
    private MaterialCardView btnAcToggle;
    private ImageView ivAcToggleIcon;
    private TextView tvAcToggleTitle;
    private TextView tvAcTogglePreset;
    private Button btnAcAdjust;
    private AlertDialog climateSettingsDialog;
    private TextView tvClimateModeBadge;
    private TextView tvPopupTargetTemp;
    private View layoutPopupFanControl;
    private TextView tvPopupFanLevel;
    private View layoutPopupVentControl;
    private MaterialButton btnPopupVentBody;
    private MaterialButton btnPopupVentBodyFeet;
    private MaterialButton btnPopupVentFeet;
    private Button btnPopupApply;
    private Button btnLights;
    private Button btnHorn;
    private View layoutComfortRow1;
    private View layoutComfortRow2;
    private Button btnVentWindows;
    private Button btnCloseWindows;
    private Button btnBatHeat;
    private Button btnSeatHeat;
    private View layoutMultiCarBar;
    private Button btnSwitchActiveCar;
    private View cardLocation;
    private TextView tvCarLocation;
    private TextView tvCarLocationHint;
    private Button btnRefreshLocation;
    private TextView tvHealthMeta;
    private TextView tvTpmsLf;
    private TextView tvTpmsRf;
    private TextView tvTpmsLr;
    private TextView tvTpmsRr;
    private Button btnUnitDist;
    private Button btnUnitTemp;
    private Button btnUnitPres;

    private TextView tvWatchStatus;
    private TextView tvServiceStatus;
    private TextView tvCheckAccount;
    private TextView tvCheckHealth;
    private TextView tvCheckBattery;
    private Button btnWatchTargetToggle;
    private TextView tvSyncIntervalLabel;
    private EditText etSyncInterval;
    private Button btnSyncIntervalUnit;
    private Button btnApplySyncInterval;
    private EditText etCustomModelName;
    private Button btnSaveCustomModel;
    private MaterialButtonToggleGroup toggleWidgetStyle;
    private TextView tvWidgetStyleDesc;
    private boolean updatingWidgetStyleUi = false;
    private MaterialButtonToggleGroup toggleAppLanguage;
    private boolean updatingAppLanguageUi = false;
    private SwitchCompat switchManualAc;
    private SwitchCompat switchHideExtraControls;
    private SwitchCompat switchSmartAlerts;
    private SwitchCompat switchVerboseLogs;
    private Button btnDisclaimer;
    private Button btnAuthHealth;
    private Button btnToggleService;
    private Button btnBatteryOpt;
    private Button btnCopyLog;
    private Button btnClearLog;
    private Button btnAboutGithubCompanion;
    private Button btnAboutGithubWatch;
    private NestedScrollView scrollStatusLog;
    private TextView tvStatusLog;
    private volatile String lastLocationLookupKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoredPageId = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_CURRENT_PAGE_ID, 0)
                : 0;
        // Explicitly allow screenshots on companion UI while keeping saved secrets unexposed.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        BydWatchApp app = (BydWatchApp) getApplication();
        if (app == null) {
            app = BydWatchApp.getInstance();
        }
        configStore = app.getConfigStore();
        if (com.bydwatch.companion.BuildConfig.DEBUG && getIntent() != null && getIntent().hasExtra("demo_lang")) {
            configStore.setAppLanguage(getIntent().getStringExtra("demo_lang"));
        }
        AppLocaleHelper.applyLanguage(configStore.getAppLanguage());
        gateway = app.getGateway();
        repository = app.getRepository();
        bridge = app.getWearEngineBridge();

        bindViews();
        initValues();
        setupListeners();
        setupBackNavigation();

        if (bridge != null) {
            stateListener = state -> runOnUiThread(this::updateWatchStatus);
            bridge.addStateListener(stateListener);
        }

        serviceStateListener = running -> runOnUiThread(this::updateWatchStatus);
        WearBridgeService.addStateListener(serviceStateListener);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_PAGE_ID, currentPageId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWatchStatus();
    }

    @Override
    protected void onDestroy() {
        climateDebounceHandler.removeCallbacks(pendingClimateSend);
        if (climateSettingsDialog != null && climateSettingsDialog.isShowing()) {
            climateSettingsDialog.dismiss();
        }
        climateSettingsDialog = null;
        if (bridge != null && stateListener != null) {
            bridge.removeStateListener(stateListener);
        }
        if (serviceStateListener != null) {
            WearBridgeService.removeStateListener(serviceStateListener);
        }
        super.onDestroy();
    }

    private void bindViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        if (drawerLayout != null) {
            int statusScrim = ContextCompat.getColor(this, R.color.bg_obsidian);
            drawerLayout.setStatusBarBackgroundColor(statusScrim);
            getWindow().setStatusBarColor(statusScrim);
        }
        navView = findViewById(R.id.navView);
        btnHamburger = findViewById(R.id.btnHamburger);
        if (navView != null && navView.getHeaderCount() > 0) {
            View drawerHeader = navView.getHeaderView(0);
            tvDrawerCarModel = drawerHeader.findViewById(R.id.tvDrawerCarModel);
            tvDrawerAccount = drawerHeader.findViewById(R.id.tvDrawerAccount);
        }

        rootScroll = findViewById(R.id.rootScroll);
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle);
        tvHeaderSubtitle = findViewById(R.id.tvHeaderSubtitle);
        cardControls = findViewById(R.id.cardControls);
        cardAccount = findViewById(R.id.cardAccount);
        cardPreferences = findViewById(R.id.cardPreferences);
        cardBridge = findViewById(R.id.cardBridge);
        layoutAccountFields = findViewById(R.id.layoutAccountFields);
        layoutSetupGuideHeader = findViewById(R.id.layoutSetupGuideHeader);
        layoutSetupGuideSteps = findViewById(R.id.layoutSetupGuideSteps);
        btnToggleSetupGuide = findViewById(R.id.btnToggleSetupGuide);
        tvAccountSummary = findViewById(R.id.tvAccountSummary);
        btnToggleAccount = findViewById(R.id.btnToggleAccount);
        tvAuthNotice = findViewById(R.id.tvAuthNotice);
        actvRegion = findViewById(R.id.actvRegion);
        etCountryCode = findViewById(R.id.etCountryCode);
        tilPassword = findViewById(R.id.tilPassword);
        tilPin = findViewById(R.id.tilPin);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etPin = findViewById(R.id.etPin);
        etVin = findViewById(R.id.etVin);
        btnSaveCredentials = findViewById(R.id.btnSaveCredentials);
        btnFetchVin = findViewById(R.id.btnFetchVin);

        tvControlsTitle = findViewById(R.id.tvControlsTitle);
        tvSoc = findViewById(R.id.tvSoc);
        pbBatterySoc = findViewById(R.id.pbBatterySoc);
        tvRange = findViewById(R.id.tvRange);
        tvChargingBadge = findViewById(R.id.tvChargingBadge);
        tvOpeningBadge = findViewById(R.id.tvOpeningBadge);
        layoutCabinTemp = findViewById(R.id.layoutCabinTemp);
        tvLockState = findViewById(R.id.tvLockState);
        tvCabinTemp = findViewById(R.id.tvCabinTemp);
        tvAcState = findViewById(R.id.tvAcState);
        tvLastUpdated = findViewById(R.id.tvLastUpdated);

        btnRefresh = findViewById(R.id.btnRefresh);
        btnLock = findViewById(R.id.btnLock);
        btnUnlock = findViewById(R.id.btnUnlock);
        btnAcToggle = findViewById(R.id.btnAcToggle);
        ivAcToggleIcon = findViewById(R.id.ivAcToggleIcon);
        tvAcToggleTitle = findViewById(R.id.tvAcToggleTitle);
        tvAcTogglePreset = findViewById(R.id.tvAcTogglePreset);
        btnAcAdjust = findViewById(R.id.btnAcAdjust);
        btnLights = findViewById(R.id.btnLights);
        btnHorn = findViewById(R.id.btnHorn);
        layoutComfortRow1 = findViewById(R.id.layoutComfortRow1);
        layoutComfortRow2 = findViewById(R.id.layoutComfortRow2);
        btnVentWindows = findViewById(R.id.btnVentWindows);
        btnCloseWindows = findViewById(R.id.btnCloseWindows);
        btnBatHeat = findViewById(R.id.btnBatHeat);
        btnSeatHeat = findViewById(R.id.btnSeatHeat);
        layoutMultiCarBar = findViewById(R.id.layoutMultiCarBar);
        btnSwitchActiveCar = findViewById(R.id.btnSwitchActiveCar);
        cardLocation = findViewById(R.id.cardLocation);
        tvCarLocation = findViewById(R.id.tvCarLocation);
        tvCarLocationHint = findViewById(R.id.tvCarLocationHint);
        btnRefreshLocation = findViewById(R.id.btnRefreshLocation);
        tvHealthMeta = findViewById(R.id.tvHealthMeta);
        tvTpmsLf = findViewById(R.id.tvTpmsLf);
        tvTpmsRf = findViewById(R.id.tvTpmsRf);
        tvTpmsLr = findViewById(R.id.tvTpmsLr);
        tvTpmsRr = findViewById(R.id.tvTpmsRr);
        btnUnitDist = findViewById(R.id.btnUnitDist);
        btnUnitTemp = findViewById(R.id.btnUnitTemp);
        btnUnitPres = findViewById(R.id.btnUnitPres);

        tvWatchStatus = findViewById(R.id.tvWatchStatus);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvCheckAccount = findViewById(R.id.tvCheckAccount);
        tvCheckHealth = findViewById(R.id.tvCheckHealth);
        tvCheckBattery = findViewById(R.id.tvCheckBattery);
        btnWatchTargetToggle = findViewById(R.id.btnWatchTargetToggle);
        tvSyncIntervalLabel = findViewById(R.id.tvSyncIntervalLabel);
        etSyncInterval = findViewById(R.id.etSyncInterval);
        btnSyncIntervalUnit = findViewById(R.id.btnSyncIntervalUnit);
        btnApplySyncInterval = findViewById(R.id.btnApplySyncInterval);
        etCustomModelName = findViewById(R.id.etCustomModelName);
        btnSaveCustomModel = findViewById(R.id.btnSaveCustomModel);
        toggleWidgetStyle = findViewById(R.id.toggleWidgetStyle);
        tvWidgetStyleDesc = findViewById(R.id.tvWidgetStyleDesc);
        toggleAppLanguage = findViewById(R.id.toggleAppLanguage);
        switchManualAc = findViewById(R.id.switchManualAc);
        switchHideExtraControls = findViewById(R.id.switchHideExtraControls);
        switchSmartAlerts = findViewById(R.id.switchSmartAlerts);
        switchVerboseLogs = findViewById(R.id.switchVerboseLogs);
        btnDisclaimer = findViewById(R.id.btnDisclaimer);
        btnAuthHealth = findViewById(R.id.btnAuthHealth);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnAboutGithubCompanion = findViewById(R.id.btnAboutGithubCompanion);
        btnAboutGithubWatch = findViewById(R.id.btnAboutGithubWatch);
        scrollStatusLog = findViewById(R.id.scrollStatusLog);
        tvStatusLog = findViewById(R.id.tvStatusLog);

        if (scrollStatusLog != null) {
            scrollStatusLog.setOnTouchListener((v, event) -> {
                if (v.getParent() != null) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                return false;
            });
        }
    }

    private void initValues() {
        // Never pre-fill saved password or PIN back into editable inputs.
        etPassword.setText("");
        etPin.setText("");

        if (actvRegion != null) {
            ArrayAdapter<RegionOption> regionAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, RegionOption.ALL_REGIONS);
            actvRegion.setAdapter(regionAdapter);
        }

        String regionCode = configStore != null ? configStore.getRegion() : SecureConfigStore.DEFAULT_REGION;
        if (actvRegion != null) {
            actvRegion.setText(RegionOption.findByCode(regionCode).formatLabel(), false);
        }
        if (etCountryCode != null && configStore != null) {
            String cc = configStore.getCountryCode();
            etCountryCode.setText(cc != null ? cc : "");
        }

        if (configStore != null) {
            String u = configStore.getUsername();
            if (u != null) etUsername.setText(u);

            String vin = configStore.getVin();
            if (vin != null) etVin.setText(vin);

            targetTempC = configStore.getDefaultTempC();
            if (targetTempC < WatchProtocol.MIN_TEMP_C || targetTempC > WatchProtocol.MAX_TEMP_C) {
                targetTempC = WatchProtocol.DEFAULT_TEMP_C;
            }
            targetTempGear = configStore.getDefaultTempGear();
            targetFanLevel = configStore.getDefaultFanLevel();
            targetWindMode = configStore.getDefaultWindMode();
            if (switchManualAc != null) {
                updatingManualAcSwitch = true;
                switchManualAc.setChecked(configStore.isManualAcMode());
                updatingManualAcSwitch = false;
            }
            if (switchSmartAlerts != null) {
                switchSmartAlerts.setChecked(configStore.isAlertsEnabled());
            }
            if (switchVerboseLogs != null) {
                switchVerboseLogs.setChecked(configStore.isVerboseLogsEnabled());
            }
            if (switchHideExtraControls != null) {
                switchHideExtraControls.setChecked(configStore.isHideExtraControls());
            }
            updateExtraControlsVisibility();
            int intervalMin = configStore.getWidgetSyncIntervalMinutes();
            syncIntervalUnitHours = intervalMin >= 60 && intervalMin % 60 == 0;
            if (etSyncInterval != null) {
                etSyncInterval.setText(String.valueOf(syncIntervalUnitHours ? (intervalMin / 60) : intervalMin));
            }
            updateSyncIntervalUi(intervalMin);
            if (etCustomModelName != null) {
                etCustomModelName.setText(configStore.getCustomVehicleName());
            }
            updateWidgetStyleUi(configStore.getWidgetStyle());
            updateAppLanguageUi(configStore.getAppLanguage());
        }
        updateClimateControlsUi();
        updateUnitButtons();
        updateWatchTargetLabel();
        updateHeaderVehicleInfo(null);
        updateMultiCarBar(null);

        if (repository != null && repository.getCachedStatus() != null) {
            updateTelemetryUi(repository.getCachedStatus());
        }

        boolean configured = configStore != null && configStore.isConfigured();
        setAccountExpanded(!configured);
        setSetupGuideExpanded(!configured);
        updateAccountUiState();
        updateWatchStatus();
        int initialPage = restoredPageId != 0
                ? restoredPageId
                : (configured ? R.id.nav_controls : R.id.nav_account);
        showPage(initialPage);

        if (com.bydwatch.companion.BuildConfig.DEBUG && getIntent() != null
                && getIntent().getBooleanExtra("demo_mode", false)) {
            applyDebugDemoState(getIntent());
        }
    }

    private void applyDebugDemoState(Intent intent) {
        if (!com.bydwatch.companion.BuildConfig.DEBUG || configStore == null) {
            return;
        }
        configStore.setRegion("id");
        configStore.setUsername("demo.driver@example.com");
        configStore.setPassword("DemoPass123!");
        configStore.setPin("000000");
        configStore.setVin("LGX0DEMO000000001");
        configStore.setVehicleName("BYD Atto 1");
        configStore.setCustomVehicleName("BYD Atto 1");
        configStore.setManualAcMode(true);
        configStore.setDefaultTempGear(2);
        configStore.setDefaultFanLevel(4);
        configStore.setDefaultWindMode(ConfigStore.WIND_MODE_BODY_FEET);
        if (intent.hasExtra("demo_widget_style")) {
            configStore.setWidgetStyle(intent.getIntExtra("demo_widget_style", ConfigStore.WIDGET_STYLE_CAPSULE));
        }
        targetTempGear = 2;
        targetFanLevel = 4;
        targetWindMode = ConfigStore.WIND_MODE_BODY_FEET;
        if (switchManualAc != null) {
            updatingManualAcSwitch = true;
            switchManualAc.setChecked(true);
            updatingManualAcSwitch = false;
        }

        double demoLat = -6.19500;
        double demoLng = 106.82300;
        VehicleLocationHelper.seedStreetCacheForDebug(demoLat, demoLng, "Jl. M. H. Thamrin, Jakarta");

        VehicleStatus demo = new VehicleStatus.Builder(78, 268, true, true, 23, 18, System.currentTimeMillis() / 1000L)
                .vin("LGX0DEMO000000001")
                .modelName("BYD Atto 1")
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargePowerKw(7.2)
                .chargeRemainingMinutes(95)
                .totalMileageKm(12450)
                .outsideTempC(31)
                .tirePressuresKpa(245, 245, 240, 240)
                .manualAc(true)
                .acPreset(2, 4, ConfigStore.WIND_MODE_BODY_FEET)
                .capabilities(true, true, true)
                .location(demoLat, demoLng, System.currentTimeMillis() / 1000L)
                .build();

        if (repository != null) {
            repository.setCachedStatus(demo);
        }
        VehicleWidgetProvider.recordSuccessfulSync(this, demo);
        VehicleWidgetProvider.refreshWidgets(this);

        if (etUsername != null) etUsername.setText("demo.driver@example.com");
        if (etVin != null) etVin.setText("LGX0DEMO000000001");
        if (etCustomModelName != null) etCustomModelName.setText("BYD Atto 1");
        updateAccountUiState();
        updateWidgetStyleUi(configStore.getWidgetStyle());
        updateAppLanguageUi(configStore.getAppLanguage());
        updateTelemetryUi(demo);
        updateHeaderVehicleInfo(demo);
        updateClimateControlsUi();
        setAccountExpanded(false);
        setSetupGuideExpanded(intent.getBooleanExtra("demo_setup_guide", false));

        String page = intent.getStringExtra("demo_page");
        if ("account".equalsIgnoreCase(page)) {
            showPage(R.id.nav_account);
        } else if ("preferences".equalsIgnoreCase(page)) {
            showPage(R.id.nav_preferences);
        } else if ("watch".equalsIgnoreCase(page)) {
            showPage(R.id.nav_watch_bridge);
        } else {
            showPage(R.id.nav_controls);
        }

        if (intent.getBooleanExtra("demo_climate_dialog", false)) {
            new Handler(Looper.getMainLooper()).post(this::showClimateSettingsPopup);
        } else if (intent.getBooleanExtra("demo_pin_widget", false)
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.appwidget.AppWidgetManager awm = android.appwidget.AppWidgetManager.getInstance(this);
            if (awm != null && awm.isRequestPinAppWidgetSupported()) {
                awm.requestPinAppWidget(new android.content.ComponentName(this, VehicleWidgetProvider.class),
                        null, null);
            }
        }
    }

    private void setAccountExpanded(boolean expanded) {
        if (layoutAccountFields != null) {
            layoutAccountFields.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (btnToggleAccount != null) {
            btnToggleAccount.setText(expanded ? R.string.btn_hide_account : R.string.btn_edit_account);
        }
    }

    private void setSetupGuideExpanded(boolean expanded) {
        if (layoutSetupGuideSteps != null) {
            layoutSetupGuideSteps.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (btnToggleSetupGuide != null) {
            btnToggleSetupGuide.setText(expanded ? R.string.btn_hide_guide : R.string.btn_show_guide);
        }
    }

    private void updateAccountUiState() {
        boolean hasPassword = hasSavedPassword();
        boolean hasPin = hasSavedPin();
        if (tilPassword != null) {
            tilPassword.setHelperText(getString(
                    hasPassword ? R.string.helper_password_saved : R.string.helper_password_required));
        }
        if (tilPin != null) {
            tilPin.setHelperText(getString(
                    hasPin ? R.string.helper_pin_saved : R.string.helper_pin_required));
        }
        if (tvAccountSummary != null) {
            String savedUser = configStore != null ? configStore.getUsername() : null;
            String savedVin = configStore != null ? configStore.getVin() : null;
            if (savedUser != null && !savedUser.trim().isEmpty() && hasPassword && hasPin) {
                String summary = maskUsername(savedUser.trim()) + "  •  PIN ••••••";
                if (savedVin != null && !savedVin.trim().isEmpty()) {
                    summary += "  •  VIN " + maskVin(savedVin.trim());
                }
                tvAccountSummary.setText(summary);
                tvAccountSummary.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
                if (tvDrawerAccount != null) {
                    tvDrawerAccount.setText(maskUsername(savedUser.trim()));
                }
            } else {
                tvAccountSummary.setText(R.string.account_summary_unconfigured);
                tvAccountSummary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                if (tvDrawerAccount != null) {
                    tvDrawerAccount.setText(R.string.account_summary_unconfigured);
                }
            }
        }
    }

    private boolean hasSavedPassword() {
        String p = configStore != null ? configStore.getPassword() : null;
        return p != null && !p.trim().isEmpty();
    }

    private boolean hasSavedPin() {
        String pin = configStore != null ? configStore.getPin() : null;
        return pin != null && !pin.trim().isEmpty();
    }

    private static String maskUsername(String raw) {
        int atIdx = raw.indexOf('@');
        if (atIdx > 2) {
            return raw.substring(0, 2) + "***" + raw.substring(atIdx);
        }
        if (raw.length() > 4) {
            return raw.substring(0, 2) + "***" + raw.substring(raw.length() - 2);
        }
        return "***";
    }

    private static String maskVin(String vin) {
        if (vin == null) {
            return "••••";
        }
        String clean = vin.trim();
        if (clean.length() <= 4) {
            return "••••";
        }
        return "••••" + clean.substring(clean.length() - 4);
    }

    private void updateWatchStatus() {
        if (tvWatchStatus != null) {
            if (bridge != null && bridge.getConnectionState() == WearEngineBridge.ConnectionState.LISTENING) {
                tvWatchStatus.setText(configStore != null && configStore.isConfigured()
                        ? getString(R.string.status_bridge_listening)
                        : "BYD unconfigured — " + getString(R.string.status_bridge_listening));
                tvWatchStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            } else if (bridge != null && bridge.getConnectionState() == WearEngineBridge.ConnectionState.PERMISSION_NEEDED) {
                tvWatchStatus.setText(configStore != null && configStore.isConfigured()
                        ? getString(R.string.status_health_needed)
                        : "BYD unconfigured — " + getString(R.string.status_health_needed));
                tvWatchStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_rose));
            } else if (bridge != null && bridge.getConnectionState() == WearEngineBridge.ConnectionState.UNAVAILABLE) {
                tvWatchStatus.setText(getString(R.string.status_bridge_unavailable));
                tvWatchStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            } else if (configStore == null || !configStore.isConfigured()) {
                tvWatchStatus.setText(R.string.watch_status_unconfigured);
                tvWatchStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
            } else {
                tvWatchStatus.setText(R.string.watch_status_ready);
                tvWatchStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            }
        }

        if (tvServiceStatus != null && btnToggleService != null) {
            boolean isRunning = WearBridgeService.isRunning();
            if (isRunning) {
                tvServiceStatus.setText(R.string.service_status_running);
                btnToggleService.setText(R.string.btn_stop_service);
            } else {
                tvServiceStatus.setText(R.string.service_status_stopped);
                btnToggleService.setText(R.string.btn_start_service);
            }
        }
        updateReadinessChecklist();
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return;
                }
                if (currentPageId != R.id.nav_controls
                        && configStore != null && configStore.isConfigured()) {
                    showPage(R.id.nav_controls);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
    }

    private void showPage(int navItemId) {
        currentPageId = navItemId;
        if (cardControls != null) {
            cardControls.setVisibility(navItemId == R.id.nav_controls ? View.VISIBLE : View.GONE);
        }
        if (cardAccount != null) {
            cardAccount.setVisibility(navItemId == R.id.nav_account ? View.VISIBLE : View.GONE);
        }
        if (cardPreferences != null) {
            cardPreferences.setVisibility(navItemId == R.id.nav_preferences ? View.VISIBLE : View.GONE);
        }
        if (cardBridge != null) {
            cardBridge.setVisibility(navItemId == R.id.nav_watch_bridge ? View.VISIBLE : View.GONE);
        }
        if (navView != null) {
            navView.setCheckedItem(navItemId);
        }
        updateHeaderSubtitleForPage(navItemId);
        if (rootScroll != null) {
            rootScroll.scrollTo(0, 0);
        }
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    private void updateHeaderSubtitleForPage(int navItemId) {
        if (tvHeaderSubtitle == null) return;
        String regionCode = configStore != null ? configStore.getRegion() : SecureConfigStore.DEFAULT_REGION;
        RegionOption opt = RegionOption.findByCode(regionCode);
        if (navItemId == R.id.nav_account) {
            tvHeaderSubtitle.setText(getString(R.string.card_account_title));
        } else if (navItemId == R.id.nav_preferences) {
            tvHeaderSubtitle.setText(getString(R.string.card_preferences_title));
        } else if (navItemId == R.id.nav_watch_bridge) {
            tvHeaderSubtitle.setText(getString(R.string.nav_watch_bridge));
        } else {
            tvHeaderSubtitle.setText(opt.formatLabel());
        }
    }

    private void setupListeners() {
        if (btnHamburger != null && drawerLayout != null) {
            btnHamburger.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }
        if (navView != null) {
            navView.setNavigationItemSelectedListener(item -> {
                showPage(item.getItemId());
                return true;
            });
        }
        if (btnToggleAccount != null) {
            btnToggleAccount.setOnClickListener(v -> {
                boolean isExpanded = layoutAccountFields != null
                        && layoutAccountFields.getVisibility() == View.VISIBLE;
                setAccountExpanded(!isExpanded);
            });
        }
        View.OnClickListener setupGuideToggle = v -> {
            boolean isExpanded = layoutSetupGuideSteps != null
                    && layoutSetupGuideSteps.getVisibility() == View.VISIBLE;
            setSetupGuideExpanded(!isExpanded);
        };
        if (btnToggleSetupGuide != null) {
            btnToggleSetupGuide.setOnClickListener(setupGuideToggle);
        }
        if (layoutSetupGuideHeader != null) {
            layoutSetupGuideHeader.setOnClickListener(setupGuideToggle);
        }
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> copyEventLog());
        }
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                tvStatusLog.setText("");
                logMessage("Log cleared.");
            });
        }

        btnSaveCredentials.setOnClickListener(v -> saveCredentials());
        btnFetchVin.setOnClickListener(v -> fetchVinAndTestLogin());

        if (btnAcToggle != null) {
            btnAcToggle.setOnClickListener(v -> toggleAcState());
        }
        if (btnAcAdjust != null) {
            btnAcAdjust.setOnClickListener(v -> showClimateSettingsPopup());
        }

        btnRefresh.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_STATUS, -1));
        btnLock.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_LOCK, -1));
        btnUnlock.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_UNLOCK, -1));
        btnLights.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_LIGHTS, -1));
        btnHorn.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_HORN, -1));

        if (btnVentWindows != null) {
            btnVentWindows.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_VENT_WINDOWS, -1));
        }
        if (btnCloseWindows != null) {
            btnCloseWindows.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_CLOSE_WINDOWS, -1));
        }
        if (btnBatHeat != null) {
            btnBatHeat.setOnClickListener(v -> {
                boolean currentlyOn = lastStatus != null && Boolean.TRUE.equals(lastStatus.batteryHeatOn);
                executeWatchAction(currentlyOn
                        ? WatchProtocol.ACTION_BATTERY_HEAT_OFF
                        : WatchProtocol.ACTION_BATTERY_HEAT_ON, -1);
            });
        }
        if (btnSeatHeat != null) {
            btnSeatHeat.setOnClickListener(v -> {
                int currentLevel = lastStatus != null ? lastStatus.driverSeatLevel : 0;
                int nextLevel = (currentLevel + 1) % 3;
                executeWatchAction(WatchProtocol.ACTION_SEAT_HEAT, -1, null, nextLevel);
            });
        }
        if (btnSwitchActiveCar != null) {
            btnSwitchActiveCar.setOnClickListener(v -> showMultiCarPicker());
        }
        if (cardLocation != null) {
            cardLocation.setOnClickListener(v -> onLocationCardClicked());
        }
        if (btnRefreshLocation != null) {
            btnRefreshLocation.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_LOCATION, -1));
        }
        if (btnUnitDist != null) {
            btnUnitDist.setOnClickListener(v -> cycleDistanceUnit());
        }
        if (btnUnitTemp != null) {
            btnUnitTemp.setOnClickListener(v -> cycleTempUnit());
        }
        if (btnUnitPres != null) {
            btnUnitPres.setOnClickListener(v -> cyclePressureUnit());
        }
        if (btnWatchTargetToggle != null) {
            btnWatchTargetToggle.setOnClickListener(v -> toggleWatchTarget());
        }
        if (btnSyncIntervalUnit != null) {
            btnSyncIntervalUnit.setOnClickListener(v -> toggleSyncIntervalUnit());
        }
        if (btnApplySyncInterval != null) {
            btnApplySyncInterval.setOnClickListener(v -> saveWidgetSyncInterval());
        }
        if (btnSaveCustomModel != null) {
            btnSaveCustomModel.setOnClickListener(v -> saveCustomModelName());
        }
        if (toggleAppLanguage != null) {
            toggleAppLanguage.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked || updatingAppLanguageUi) return;
                String lang = appLanguageForButtonId(checkedId);
                if (configStore != null) {
                    configStore.setAppLanguage(lang);
                }
                updateAppLanguageUi(lang);
                VehicleWidgetProvider.refreshWidgets(this);
                LocationWidgetProvider.refreshWidgets(this);
                Context resCtx = AppLocaleHelper.localizedContext(this, configStore);
                String label = appLanguageDisplayLabel(resCtx, lang);
                showToast(resCtx.getString(R.string.lang_changed_toast, label), false);
                AppLocaleHelper.applyLanguage(lang);
            });
        }
        if (toggleWidgetStyle != null) {
            toggleWidgetStyle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked || updatingWidgetStyleUi) return;
                int style = widgetStyleForButtonId(checkedId);
                if (configStore != null) {
                    configStore.setWidgetStyle(style);
                }
                updateWidgetStyleUi(style);
                VehicleWidgetProvider.refreshWidgets(this);
                String desc = widgetStyleDescFor(style);
                logMessage("4x3 widget style set to " + desc);
                showToast(desc, false);
            });
        }
        if (switchManualAc != null) {
            switchManualAc.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingManualAcSwitch) return;
                if (configStore != null) {
                    configStore.setManualAcMode(isChecked);
                }
                updateClimateControlsUi();
                logMessage("Manual AC mode (Atto 1) " + (isChecked ? "enabled" : "disabled") + ".");
            });
        }
        if (switchHideExtraControls != null) {
            switchHideExtraControls.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (configStore != null) {
                    configStore.setHideExtraControls(isChecked);
                }
                updateExtraControlsVisibility();
            });
        }
        if (switchSmartAlerts != null) {
            switchSmartAlerts.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (configStore != null) {
                    configStore.setAlertsEnabled(isChecked);
                    logMessage("Smart alerts " + (isChecked ? "enabled" : "disabled") + ".");
                }
            });
        }
        if (switchVerboseLogs != null) {
            switchVerboseLogs.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (configStore != null) {
                    configStore.setVerboseLogsEnabled(isChecked);
                    logMessage("Extra telemetry logs " + (isChecked ? "enabled" : "disabled") + ".");
                }
            });
        }
        if (btnDisclaimer != null) {
            btnDisclaimer.setOnClickListener(v -> showDisclaimerDialog());
        }

        btnAuthHealth.setOnClickListener(v -> authorizeHuaweiHealth());
        btnToggleService.setOnClickListener(v -> toggleBridgeService());
        btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimizationExemption());
        if (btnAboutGithubCompanion != null) {
            btnAboutGithubCompanion.setOnClickListener(
                    v -> openExternalUrl(getString(R.string.about_github_companion_url)));
        }
        if (btnAboutGithubWatch != null) {
            btnAboutGithubWatch.setOnClickListener(
                    v -> openExternalUrl(getString(R.string.about_github_watch_url)));
        }
    }

    private void authorizeHuaweiHealth() {
        if (bridge == null) {
            logMessage("WearEngineBridge is unavailable.");
            return;
        }
        logMessage("Requesting Huawei Health authorization (DEVICE_MANAGER)...");
        btnAuthHealth.setEnabled(false);
        bridge.requestHealthPermission(this, new WearEngineBridge.Callback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    btnAuthHealth.setEnabled(true);
                    logMessage(getString(R.string.status_health_authorized));
                    updateWatchStatus();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnAuthHealth.setEnabled(true);
                    logMessage("Huawei Health authorization failed: " + error);
                    updateWatchStatus();
                });
            }
        });
    }

    private boolean checkAndRequestBridgePermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            logMessage("Requesting permissions: " + needed);
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), RC_PERMISSIONS);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_PERMISSIONS) {
            boolean bluetoothGranted = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                for (int i = 0; i < permissions.length; i++) {
                    if (Manifest.permission.BLUETOOTH_CONNECT.equals(permissions[i])) {
                        bluetoothGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                    }
                }
            }
            if (bluetoothGranted) {
                logMessage("Permissions granted. Starting WearBridgeService...");
                WearBridgeService.start(this);
            } else {
                logMessage("BLUETOOTH_CONNECT permission denied. Cannot start foreground service.");
            }
            updateWatchStatus();
        }
    }

    private void toggleBridgeService() {
        if (WearBridgeService.isRunning()) {
            WearBridgeService.stop(this);
            logMessage("Stopping bridge service...");
        } else {
            if (checkAndRequestBridgePermissions()) {
                WearBridgeService.start(this);
                logMessage("Starting bridge service in foreground...");
            }
        }
        updateWatchStatus();
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                logMessage(getString(R.string.battery_opt_granted));
                return;
            }
            logMessage(getString(R.string.battery_opt_prompt));
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(fallback);
                } catch (Exception ex) {
                    logMessage("Unable to open battery optimization settings: " + ex.getMessage());
                }
            }
        } else {
            logMessage(getString(R.string.battery_opt_granted));
        }
    }

    private boolean validateResolvedInputs(String u, String effectivePassword, String effectivePin,
                                           String vin, boolean requireVin) {
        if (u == null || u.isEmpty()) {
            logMessage("Validation error: Username/email cannot be empty.");
            showToast("Username/email cannot be empty", false);
            return false;
        }
        if (effectivePassword == null || effectivePassword.isEmpty()) {
            logMessage("Validation error: Password cannot be empty.");
            showToast("Password cannot be empty", false);
            return false;
        }
        if (effectivePin == null || effectivePin.isEmpty() || !effectivePin.matches("\\d{6}")) {
            logMessage("Validation error: Control PIN must be exactly 6 numeric digits.");
            showToast("Control PIN must be 6 numeric digits", false);
            return false;
        }
        if (requireVin && (vin == null || vin.isEmpty())) {
            logMessage("Validation error: VIN cannot be empty.");
            showToast("VIN cannot be empty", false);
            return false;
        }
        return true;
    }

    private boolean persistValidatedInputs(boolean requireVin) {
        String u = etUsername.getText().toString().trim();
        String enteredPassword = etPassword.getText().toString().trim();
        String enteredPin = etPin.getText().toString().trim();
        String vin = etVin.getText().toString().trim();

        String savedPassword = configStore != null ? configStore.getPassword() : null;
        String savedPin = configStore != null ? configStore.getPin() : null;
        String effectivePassword = !enteredPassword.isEmpty()
                ? enteredPassword
                : (savedPassword != null ? savedPassword.trim() : "");
        String effectivePin = !enteredPin.isEmpty()
                ? enteredPin
                : (savedPin != null ? savedPin.trim() : "");

        if (!validateResolvedInputs(u, effectivePassword, effectivePin, vin, requireVin)) {
            return false;
        }

        if (configStore != null) {
            String selectedRegion = SecureConfigStore.DEFAULT_REGION;
            if (actvRegion != null && actvRegion.getText() != null) {
                selectedRegion = RegionOption.extractCodeFromText(actvRegion.getText().toString());
            }
            String countryCode = etCountryCode != null && etCountryCode.getText() != null
                    ? etCountryCode.getText().toString().trim().toUpperCase(Locale.US)
                    : "";

            configStore.setUsername(u);
            configStore.setPassword(effectivePassword);
            configStore.setPin(effectivePin);
            if (!vin.isEmpty()) {
                configStore.setVin(vin);
            }
            configStore.setRegion(selectedRegion);
            configStore.setCountryCode(countryCode);
            configStore.setDefaultTempC(targetTempC);
        }

        // Clear typed secret fields immediately after persisting so saved secrets stay hidden.
        etPassword.setText("");
        etPin.setText("");
        etPassword.clearFocus();
        etPin.clearFocus();
        updateAccountUiState();
        return true;
    }

    private void saveCredentials() {
        if (!persistValidatedInputs(false)) {
            return;
        }

        if (configStore != null && configStore.isConfigured()) {
            setAccountExpanded(false);
            showPage(R.id.nav_controls);
        }
        updateWatchStatus();
        logMessage("Credentials saved successfully.");
        showToast("Credentials saved", false);
    }

    private void fetchVinAndTestLogin() {
        if (!persistValidatedInputs(false)) {
            return;
        }

        btnFetchVin.setEnabled(false);
        logMessage("Logging in and fetching vehicles...");

        repository.fetchVehicles(new BydRepository.VehiclesCallback() {
            @Override
            public void onSuccess(List<VehicleSummary> vehicles, VehicleSummary active) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    String activeVin = active != null ? active.vin : "";
                    etVin.setText(activeVin);
                    updateAccountUiState();
                    updateHeaderVehicleInfo(null);
                    updateMultiCarBar(null);
                    if (configStore != null && configStore.isConfigured()) {
                        setAccountExpanded(false);
                        showPage(R.id.nav_controls);
                    }
                    int count = vehicles != null ? vehicles.size() : (activeVin.isEmpty() ? 0 : 1);
                    logMessage("Login successful. Found VIN: " + maskVin(activeVin)
                            + (count > 1 ? " (" + count + " vehicles)" : ""));
                    updateWatchStatus();
                    executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    logMessage("Login & Fetch VIN failed: " + error);
                    showToast("Login & Fetch VIN failed: " + error, true);
                });
            }
        });
    }

    private void toggleAcState() {
        climateDebounceHandler.removeCallbacks(pendingClimateSend);
        if (lastStatus != null && lastStatus.acOn) {
            executeWatchAction(WatchProtocol.ACTION_AC_OFF, -1);
        } else {
            sendCurrentAcOnCommand();
        }
    }

    private void showClimateSettingsPopup() {
        if (isFinishing() || isDestroyed()) return;
        if (climateSettingsDialog != null && climateSettingsDialog.isShowing()) {
            updateClimatePopupUi();
            return;
        }
        View popupView = LayoutInflater.from(this).inflate(R.layout.dialog_climate_settings, null);
        tvClimateModeBadge = popupView.findViewById(R.id.tvClimateModeBadge);
        tvPopupTargetTemp = popupView.findViewById(R.id.tvTargetTemp);
        layoutPopupFanControl = popupView.findViewById(R.id.layoutFanControl);
        tvPopupFanLevel = popupView.findViewById(R.id.tvFanLevel);
        layoutPopupVentControl = popupView.findViewById(R.id.layoutVentControl);
        btnPopupVentBody = popupView.findViewById(R.id.btnVentBody);
        btnPopupVentBodyFeet = popupView.findViewById(R.id.btnVentBodyFeet);
        btnPopupVentFeet = popupView.findViewById(R.id.btnVentFeet);
        btnPopupApply = popupView.findViewById(R.id.btnClimateApply);

        Button btnMinus = popupView.findViewById(R.id.btnTempMinus);
        Button btnPlus = popupView.findViewById(R.id.btnTempPlus);
        Button btnFanMinus = popupView.findViewById(R.id.btnFanMinus);
        Button btnFanPlus = popupView.findViewById(R.id.btnFanPlus);
        ImageButton btnClose = popupView.findViewById(R.id.btnClimateClose);
        Button btnDone = popupView.findViewById(R.id.btnClimateDone);

        if (btnMinus != null) {
            btnMinus.setOnClickListener(v -> adjustTargetTemp(-1));
        }
        if (btnPlus != null) {
            btnPlus.setOnClickListener(v -> adjustTargetTemp(1));
        }
        if (btnFanMinus != null) {
            btnFanMinus.setOnClickListener(v -> adjustFanLevel(-1));
        }
        if (btnFanPlus != null) {
            btnFanPlus.setOnClickListener(v -> adjustFanLevel(1));
        }
        if (btnPopupVentBody != null) {
            btnPopupVentBody.setOnClickListener(v -> selectWindMode(ConfigStore.WIND_MODE_BODY));
        }
        if (btnPopupVentBodyFeet != null) {
            btnPopupVentBodyFeet.setOnClickListener(v -> selectWindMode(ConfigStore.WIND_MODE_BODY_FEET));
        }
        if (btnPopupVentFeet != null) {
            btnPopupVentFeet.setOnClickListener(v -> selectWindMode(ConfigStore.WIND_MODE_FEET));
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(popupView)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.setOnDismissListener(d -> clearClimatePopupRefs());

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }
        if (btnDone != null) {
            btnDone.setOnClickListener(v -> dialog.dismiss());
        }
        if (btnPopupApply != null) {
            btnPopupApply.setOnClickListener(v -> {
                climateDebounceHandler.removeCallbacks(pendingClimateSend);
                sendCurrentAcOnCommand();
                dialog.dismiss();
            });
        }

        climateSettingsDialog = dialog;
        updateClimatePopupUi();
        dialog.show();
    }

    private void clearClimatePopupRefs() {
        climateSettingsDialog = null;
        tvClimateModeBadge = null;
        tvPopupTargetTemp = null;
        layoutPopupFanControl = null;
        tvPopupFanLevel = null;
        layoutPopupVentControl = null;
        btnPopupVentBody = null;
        btnPopupVentBodyFeet = null;
        btnPopupVentFeet = null;
        btnPopupApply = null;
    }

    private void adjustTargetTemp(int delta) {
        if (isManualAcActive()) {
            int next = Math.max(ConfigStore.MIN_TEMP_GEAR, Math.min(ConfigStore.MAX_TEMP_GEAR, targetTempGear + delta));
            if (next != targetTempGear) {
                targetTempGear = next;
                if (configStore != null) {
                    configStore.setDefaultTempGear(targetTempGear);
                }
                updateClimateControlsUi();
                scheduleDebouncedClimateUpdateIfAcOn();
            }
        } else {
            int next = Math.max(WatchProtocol.MIN_TEMP_C, Math.min(WatchProtocol.MAX_TEMP_C, targetTempC + delta));
            if (next != targetTempC) {
                targetTempC = next;
                if (configStore != null) {
                    configStore.setDefaultTempC(targetTempC);
                }
                updateClimateControlsUi();
                scheduleDebouncedClimateUpdateIfAcOn();
            }
        }
    }

    private void adjustFanLevel(int delta) {
        int next = Math.max(ConfigStore.MIN_FAN_LEVEL, Math.min(ConfigStore.MAX_FAN_LEVEL, targetFanLevel + delta));
        if (next != targetFanLevel) {
            targetFanLevel = next;
            if (configStore != null) {
                configStore.setDefaultFanLevel(targetFanLevel);
            }
            updateClimateControlsUi();
            scheduleDebouncedClimateUpdateIfAcOn();
        }
    }

    private void selectWindMode(int windMode) {
        if (windMode != targetWindMode) {
            targetWindMode = windMode;
            if (configStore != null) {
                configStore.setDefaultWindMode(targetWindMode);
            }
            updateClimateControlsUi();
            scheduleDebouncedClimateUpdateIfAcOn();
        }
    }

    private void scheduleDebouncedClimateUpdateIfAcOn() {
        climateDebounceHandler.removeCallbacks(pendingClimateSend);
        if (lastStatus != null && lastStatus.acOn) {
            climateDebounceHandler.postDelayed(pendingClimateSend, CLIMATE_DEBOUNCE_MS);
        }
    }

    private void sendCurrentAcOnCommand() {
        if (isManualAcActive()) {
            executeWatchAction(WatchProtocol.ACTION_AC_ON, targetTempC, null, -1,
                    targetTempGear, targetFanLevel, targetWindMode);
        } else {
            executeWatchAction(WatchProtocol.ACTION_AC_ON, targetTempC);
        }
    }

    private void executeWatchAction(String action, int tempC) {
        executeWatchAction(action, tempC, null, -1, -1, -1, -1);
    }

    private void executeWatchAction(String action, int tempC, String vinOverride, int level) {
        executeWatchAction(action, tempC, vinOverride, level, -1, -1, -1);
    }

    private void executeWatchAction(String action, int tempC, String vinOverride, int level,
                                    int tempGear, int fanLevel, int windMode) {
        if (configStore != null && !configStore.isConfigured()) {
            logMessage(action + " failed: BYD credentials are not configured.");
            showToast("BYD credentials are not configured", false);
            return;
        }

        String cmdId = "ui_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet();
        JSONObject json = new JSONObject();
        try {
            json.put("id", cmdId);
            json.put("action", action);
            if (WatchProtocol.ACTION_AC_ON.equals(action)) {
                json.put("tempC", tempC >= WatchProtocol.MIN_TEMP_C ? tempC : targetTempC);
                if (tempGear >= ConfigStore.MIN_TEMP_GEAR && tempGear <= ConfigStore.MAX_TEMP_GEAR) {
                    json.put("tempGear", tempGear);
                }
                if (fanLevel >= ConfigStore.MIN_FAN_LEVEL && fanLevel <= ConfigStore.MAX_FAN_LEVEL) {
                    json.put("fanLevel", fanLevel);
                }
                if (windMode == ConfigStore.WIND_MODE_BODY
                        || windMode == ConfigStore.WIND_MODE_BODY_FEET
                        || windMode == ConfigStore.WIND_MODE_FEET) {
                    json.put("windMode", windMode);
                }
            }
            if (vinOverride != null && !vinOverride.trim().isEmpty()) {
                json.put("vin", vinOverride.trim());
            }
            if (WatchProtocol.ACTION_SEAT_HEAT.equals(action) && level >= 0) {
                json.put("level", level);
            }
        } catch (JSONException e) {
            logMessage("Failed to build command payload for " + action + ": " + e.getMessage());
            showToast("Failed to build " + formatActionLabel(action) + " command", false);
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
                String label = formatActionLabel(resp.action);
                if (resp.ok) {
                    logMessage(resp.action + ": OK (" + (resp.msg.isEmpty() ? "Success" : resp.msg) + ")");
                    if (resp.state != null) {
                        if (WatchProtocol.ACTION_STATUS.equals(resp.action)
                                && configStore != null
                                && configStore.isVerboseLogsEnabled()
                                && repository != null) {
                            String diag = repository.getLastStatusDiag();
                            if (diag != null && !diag.isEmpty()) {
                                logMessage("[raw] " + diag);
                            }
                        }
                        VehicleWidgetProvider.recordSuccessfulSync(this, resp.state);
                        updateTelemetryUi(resp.state, !WatchProtocol.ACTION_AC_ON.equals(resp.action));
                    }
                    String toastMsg = WatchProtocol.ACTION_STATUS.equals(resp.action)
                            ? "Status updated"
                            : label + " successful";
                    showToast(toastMsg, false);
                } else {
                    String reason = !resp.msg.isEmpty() ? resp.msg : resp.err;
                    logMessage(resp.action + " failed [" + resp.err + "]: " + resp.msg);
                    showToast(label + " failed" + (reason.isEmpty() ? "" : ": " + reason), true);
                }
            } else {
                logMessage("Received null response payload.");
                showToast("Empty response from vehicle", false);
            }
        } catch (Exception e) {
            logMessage("Error processing response: " + e.getMessage());
            showToast("Error processing response", false);
        }
    }

    private String formatActionLabel(String action) {
        if (WatchProtocol.ACTION_LOCK.equals(action)) return "Lock";
        if (WatchProtocol.ACTION_UNLOCK.equals(action)) return "Unlock";
        if (WatchProtocol.ACTION_AC_ON.equals(action)) return "AC On";
        if (WatchProtocol.ACTION_AC_OFF.equals(action)) return "AC Off";
        if (WatchProtocol.ACTION_LIGHTS.equals(action)) return "Flash Lights";
        if (WatchProtocol.ACTION_HORN.equals(action)) return "Horn";
        if (WatchProtocol.ACTION_VENT_WINDOWS.equals(action)) return "Vent Windows";
        if (WatchProtocol.ACTION_CLOSE_WINDOWS.equals(action)) return "Close Windows";
        if (WatchProtocol.ACTION_BATTERY_HEAT_ON.equals(action)) return "Battery Heat On";
        if (WatchProtocol.ACTION_BATTERY_HEAT_OFF.equals(action)) return "Battery Heat Off";
        if (WatchProtocol.ACTION_SEAT_HEAT.equals(action)) return "Seat Heat";
        if (WatchProtocol.ACTION_SWITCH_CAR.equals(action)) return "Switch Vehicle";
        if (WatchProtocol.ACTION_LOCATION.equals(action)) return "GPS Location";
        if (WatchProtocol.ACTION_STATUS.equals(action)) return "Status";
        return action != null ? action : "Command";
    }

    private void showToast(String message, boolean longDuration) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> showToast(message, longDuration));
            return;
        }
        Toast.makeText(this, message, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    private void updateTelemetryUi(VehicleStatus status) {
        updateTelemetryUi(status, true);
    }

    private void updateTelemetryUi(VehicleStatus status, boolean syncActiveClimatePreset) {
        if (status == null) return;
        this.lastStatus = status;
        if (syncActiveClimatePreset && status.acOn) {
            if (status.manualAc || isManualAcActive()) {
                if (status.targetTempGear != null) {
                    targetTempGear = status.targetTempGear;
                    if (configStore != null) configStore.setDefaultTempGear(targetTempGear);
                }
                if (status.fanLevel != null) {
                    targetFanLevel = status.fanLevel;
                    if (configStore != null) configStore.setDefaultFanLevel(targetFanLevel);
                }
                if (status.windMode != null) {
                    targetWindMode = status.windMode;
                    if (configStore != null) configStore.setDefaultWindMode(targetWindMode);
                }
            } else if (status.targetTempC >= 17 && status.targetTempC <= 31) {
                targetTempC = status.targetTempC;
                if (configStore != null) configStore.setDefaultTempC(targetTempC);
            }
        }

        String distUnit = configStore != null ? configStore.getDistanceUnit() : status.distanceUnit;
        String tempUnit = configStore != null ? configStore.getTempUnit() : status.tempUnit;
        String presUnit = configStore != null ? configStore.getPressureUnit() : status.pressureUnit;

        int clampedSoc = Math.max(0, Math.min(100, status.soc));
        tvSoc.setText(status.soc + "%");
        if (pbBatterySoc != null) {
            pbBatterySoc.setProgress(clampedSoc);
        }
        int socColorRes = status.isCharging()
                ? R.color.accent_emerald
                : (clampedSoc >= 30
                        ? R.color.accent_emerald
                        : (clampedSoc >= 15 ? R.color.accent_amber : R.color.accent_rose));
        tvSoc.setTextColor(ContextCompat.getColor(this, socColorRes));
        if (pbBatterySoc != null) {
            pbBatterySoc.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, socColorRes)));
        }

        tvRange.setText(UnitPreferences.formatDistance(status.rangeKm, distUnit));
        tvLockState.setText(status.locked ? R.string.state_locked : R.string.state_unlocked);
        tvLockState.setTextColor(ContextCompat.getColor(
                this, status.locked ? R.color.accent_emerald : R.color.accent_amber));
        if (status.cabinTempC != null) {
            if (layoutCabinTemp != null) {
                layoutCabinTemp.setVisibility(View.VISIBLE);
            }
            tvCabinTemp.setText(UnitPreferences.formatTemp(status.cabinTempC, tempUnit));
        } else {
            if (layoutCabinTemp != null) {
                layoutCabinTemp.setVisibility(View.GONE);
            }
            tvCabinTemp.setText("--°" + UnitPreferences.normalizeTempUnit(tempUnit));
        }
        tvAcState.setText(status.acOn ? R.string.btn_ac_on : R.string.btn_ac_off);
        if (status.ts > 0) {
            long millis = status.ts > 1_000_000_000_000L ? status.ts : status.ts * 1000L;
            tvLastUpdated.setText(timeFormat.format(new Date(millis)));
        } else {
            tvLastUpdated.setText(R.string.state_now);
        }

        if (tvChargingBadge != null) {
            tvChargingBadge.setVisibility(View.VISIBLE);
            if (status.chargingState == VehicleStatus.CHARGING_ACTIVE) {
                StringBuilder badge = new StringBuilder(getString(R.string.state_charging));
                if (status.hasChargePowerKw()) {
                    badge.append(" · ").append(formatChargePowerKw(status.chargePowerKw));
                }
                String rem = formatChargeRemaining(status.chargeRemainingMinutes);
                if (!rem.isEmpty()) {
                    badge.append(" · ").append(rem);
                }
                tvChargingBadge.setText(badge.toString());
                tvChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            } else if (status.chargingState == VehicleStatus.CHARGING_CONNECTED) {
                tvChargingBadge.setText(R.string.state_plugged_in);
                tvChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan));
            } else if (status.vehicleOn) {
                tvChargingBadge.setText(R.string.state_driving_on);
                tvChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan));
            } else {
                tvChargingBadge.setText(R.string.state_not_charging);
                tvChargingBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            }
        }

        if (tvOpeningBadge != null) {
            tvOpeningBadge.setVisibility(View.VISIBLE);
            int openings = status.doorsOpenCount + status.windowsOpenCount + (status.trunkOpen ? 1 : 0);
            if (openings == 0) {
                tvOpeningBadge.setText(R.string.state_all_closed);
                tvOpeningBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            } else {
                tvOpeningBadge.setText(status.doorsOpenCount + "D " + status.windowsOpenCount + "W"
                        + (status.trunkOpen ? " + Trunk" : "") + " Open");
                tvOpeningBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_rose));
            }
        }

        if (tvHealthMeta != null) {
            StringBuilder meta = new StringBuilder();
            if (status.totalMileageKm != null && status.totalMileageKm > 0) {
                meta.append(getString(R.string.health_odo_fmt,
                        UnitPreferences.formatDistance(status.totalMileageKm, distUnit)));
            } else {
                meta.append(getString(R.string.health_odo_empty));
            }
            if (status.outsideTempC != null) {
                meta.append("  •  ").append(getString(R.string.health_out_fmt,
                        UnitPreferences.formatTemp(status.outsideTempC, tempUnit)));
            }
            if (status.fuelPercent != null && status.fuelPercent > 0) {
                meta.append("  •  ").append(getString(R.string.health_fuel_fmt,
                        String.valueOf(status.fuelPercent)));
            }
            tvHealthMeta.setText(meta.toString());
        }

        if (tvTpmsLf != null) tvTpmsLf.setText("LF " + UnitPreferences.formatPressure(status.tireLfKpa, presUnit));
        if (tvTpmsRf != null) tvTpmsRf.setText("RF " + UnitPreferences.formatPressure(status.tireRfKpa, presUnit));
        if (tvTpmsLr != null) tvTpmsLr.setText("LR " + UnitPreferences.formatPressure(status.tireLrKpa, presUnit));
        if (tvTpmsRr != null) tvTpmsRr.setText("RR " + UnitPreferences.formatPressure(status.tireRrKpa, presUnit));

        if (btnBatHeat != null) {
            btnBatHeat.setEnabled(status.canBatteryHeat);
            boolean batOn = Boolean.TRUE.equals(status.batteryHeatOn);
            btnBatHeat.setText(batOn ? R.string.btn_bat_heat_on : R.string.btn_bat_heat);
        }
        if (btnSeatHeat != null) {
            btnSeatHeat.setEnabled(status.canSeatClimate);
            btnSeatHeat.setText(status.driverSeatLevel > 0
                    ? getString(R.string.btn_seat_heat_level, status.driverSeatLevel)
                    : getString(R.string.btn_seat_heat));
        }

        if (status.vin != null && !status.vin.isEmpty() && etVin != null
                && !status.vin.equals(etVin.getText().toString().trim())) {
            etVin.setText(status.vin);
        }

        updateClimateControlsUi();
        updateHeaderVehicleInfo(status);
        updateMultiCarBar(status);
        updateLocationCard(status);
        updateAccountUiState();
        VehicleWidgetProvider.refreshWidgets(this);
    }

    private static String formatChargePowerKw(Double kw) {
        if (kw == null || kw.isNaN() || kw.isInfinite() || kw <= 0.0) return "";
        return String.format(Locale.US, "%.1f kW", kw);
    }

    private String formatChargeRemaining(Integer totalMinutes) {
        if (totalMinutes == null || totalMinutes <= 0) return "";
        int hrs = totalMinutes / 60;
        int mins = totalMinutes % 60;
        return getString(R.string.charge_eta_left_fmt, hrs, mins);
    }

    private void updateExtraControlsVisibility() {
        boolean hide = configStore != null && configStore.isHideExtraControls();
        int vis = hide ? View.GONE : View.VISIBLE;
        if (layoutComfortRow1 != null) layoutComfortRow1.setVisibility(vis);
        if (layoutComfortRow2 != null) layoutComfortRow2.setVisibility(vis);
    }

    private void updateLocationCard(VehicleStatus status) {
        if (tvCarLocation == null || tvCarLocationHint == null) return;
        if (status != null && status.hasLocation()) {
            final String coordKey = VehicleLocationHelper.formatCoordinates(status.latitude, status.longitude);
            lastLocationLookupKey = coordKey;
            String cachedStreet = VehicleLocationHelper.getCachedStreetName(status.latitude, status.longitude);
            tvCarLocation.setText(cachedStreet != null ? cachedStreet : coordKey);
            tvCarLocationHint.setText(R.string.label_location_tap_nav);
            tvCarLocationHint.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
            if (cachedStreet == null) {
                VehicleLocationHelper.resolveStreetName(this, status.latitude, status.longitude, street ->
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed() || street == null || street.isEmpty()) return;
                            if (lastStatus != null && lastStatus.hasLocation()
                                    && coordKey.equals(lastLocationLookupKey)) {
                                tvCarLocation.setText(street);
                                LocationWidgetProvider.refreshWidgets(MainActivity.this);
                            }
                        }));
            }
        } else {
            lastLocationLookupKey = null;
            tvCarLocation.setText(R.string.label_location_unset);
            tvCarLocationHint.setText(R.string.label_location_tap_sync);
            tvCarLocationHint.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
        }
    }

    private void onLocationCardClicked() {
        if (lastStatus != null && lastStatus.hasLocation()) {
            String preferred = VehicleWidgetProvider.resolvePreferredModelName(configStore);
            String carLabel = preferred != null
                    ? preferred
                    : ((lastStatus.modelName != null && !lastStatus.modelName.trim().isEmpty())
                            ? lastStatus.modelName.trim()
                            : (configStore != null ? configStore.getVehicleName() : "BYD Vehicle"));
            VehicleLocationHelper.showNavigatePromptDialog(
                    this, lastStatus.latitude, lastStatus.longitude, carLabel, null);
        } else {
            executeWatchAction(WatchProtocol.ACTION_LOCATION, -1);
        }
    }

    private boolean isManualAcActive() {
        if (configStore != null && configStore.getManualAcOverride() != null) {
            return configStore.getManualAcOverride().booleanValue();
        }
        if (lastStatus != null) {
            return lastStatus.manualAc;
        }
        return configStore != null && configStore.isDetectedManualAc();
    }

    private void updateClimateControlsUi() {
        boolean manual = isManualAcActive();
        boolean acOn = lastStatus != null && lastStatus.acOn;
        if (switchManualAc != null && switchManualAc.isChecked() != manual) {
            updatingManualAcSwitch = true;
            switchManualAc.setChecked(manual);
            updatingManualAcSwitch = false;
        }
        if (tvAcToggleTitle != null) {
            tvAcToggleTitle.setText(acOn ? R.string.card_ac_on_tap_stop : R.string.card_ac_off);
        }
        if (tvAcTogglePreset != null) {
            tvAcTogglePreset.setText(formatActiveClimatePreset(manual));
        }
        if (btnAcToggle != null) {
            int bgColor = MaterialColors.getColor(btnAcToggle,
                    acOn ? com.google.android.material.R.attr.colorPrimaryContainer
                            : com.google.android.material.R.attr.colorSurfaceContainer);
            int strokeColor = MaterialColors.getColor(btnAcToggle,
                    acOn ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOutlineVariant);
            int titleColor = MaterialColors.getColor(btnAcToggle,
                    acOn ? com.google.android.material.R.attr.colorOnPrimaryContainer
                            : com.google.android.material.R.attr.colorOnSurface);
            int subColor = MaterialColors.getColor(btnAcToggle,
                    acOn ? com.google.android.material.R.attr.colorOnPrimaryContainer
                            : com.google.android.material.R.attr.colorOnSurfaceVariant);
            int iconColor = MaterialColors.getColor(btnAcToggle,
                    acOn ? com.google.android.material.R.attr.colorOnPrimaryContainer
                            : com.google.android.material.R.attr.colorPrimary);
            btnAcToggle.setCardBackgroundColor(bgColor);
            btnAcToggle.setStrokeColor(strokeColor);
            if (tvAcToggleTitle != null) tvAcToggleTitle.setTextColor(titleColor);
            if (tvAcTogglePreset != null) tvAcTogglePreset.setTextColor(subColor);
            if (ivAcToggleIcon != null) ivAcToggleIcon.setColorFilter(iconColor);
        }
        updateClimatePopupUi();
    }

    private String formatActiveClimatePreset(boolean manual) {
        if (manual) {
            return getString(R.string.climate_preset_manual_fmt,
                    targetTempGear, targetFanLevel, formatWindModeLabel(targetWindMode));
        }
        String tempUnit = configStore != null ? configStore.getTempUnit() : UnitPreferences.TEMP_C;
        return UnitPreferences.formatTemp(targetTempC, tempUnit);
    }

    private String formatWindModeLabel(int windMode) {
        if (windMode == ConfigStore.WIND_MODE_BODY_FEET) return getString(R.string.vent_body_feet);
        if (windMode == ConfigStore.WIND_MODE_FEET) return getString(R.string.vent_feet);
        return getString(R.string.vent_body);
    }

    private void updateClimatePopupUi() {
        boolean manual = isManualAcActive();
        boolean acOn = lastStatus != null && lastStatus.acOn;
        if (tvClimateModeBadge != null) {
            tvClimateModeBadge.setText(manual ? R.string.climate_mode_manual : R.string.climate_mode_auto);
        }
        if (layoutPopupFanControl != null) {
            layoutPopupFanControl.setVisibility(manual ? View.VISIBLE : View.GONE);
        }
        if (layoutPopupVentControl != null) {
            layoutPopupVentControl.setVisibility(manual ? View.VISIBLE : View.GONE);
        }
        if (tvPopupFanLevel != null) {
            tvPopupFanLevel.setText(getString(R.string.climate_level_fmt, targetFanLevel));
        }
        if (tvPopupTargetTemp != null) {
            if (manual) {
                tvPopupTargetTemp.setText(getString(R.string.climate_level_fmt, targetTempGear));
            } else {
                String tempUnit = configStore != null ? configStore.getTempUnit() : UnitPreferences.TEMP_C;
                tvPopupTargetTemp.setText(UnitPreferences.formatTemp(targetTempC, tempUnit));
            }
        }
        styleVentChip(btnPopupVentBody, targetWindMode == ConfigStore.WIND_MODE_BODY);
        styleVentChip(btnPopupVentBodyFeet, targetWindMode == ConfigStore.WIND_MODE_BODY_FEET);
        styleVentChip(btnPopupVentFeet, targetWindMode == ConfigStore.WIND_MODE_FEET);
        if (btnPopupApply != null) {
            btnPopupApply.setText(acOn ? R.string.btn_apply_now : R.string.btn_start_ac);
        }
    }

    private static void styleVentChip(MaterialButton btn, boolean selected) {
        if (btn == null) return;
        int bgColor = MaterialColors.getColor(btn,
                selected ? com.google.android.material.R.attr.colorPrimaryContainer
                        : com.google.android.material.R.attr.colorSurfaceContainer);
        int textColor = MaterialColors.getColor(btn,
                selected ? com.google.android.material.R.attr.colorOnPrimaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant);
        int strokeColor = MaterialColors.getColor(btn,
                selected ? com.google.android.material.R.attr.colorPrimary
                        : com.google.android.material.R.attr.colorOutlineVariant);
        btn.setBackgroundTintList(ColorStateList.valueOf(bgColor));
        btn.setTextColor(textColor);
        btn.setStrokeColor(ColorStateList.valueOf(strokeColor));
    }

    private void updateTargetTempLabel() {
        updateClimateControlsUi();
    }

    private void updateUnitButtons() {
        String dist = configStore != null ? configStore.getDistanceUnit() : UnitPreferences.DIST_KM;
        String temp = configStore != null ? configStore.getTempUnit() : UnitPreferences.TEMP_C;
        String pres = configStore != null ? configStore.getPressureUnit() : UnitPreferences.PRES_KPA;
        if (btnUnitDist != null) btnUnitDist.setText(UnitPreferences.normalizeDistanceUnit(dist));
        if (btnUnitTemp != null) btnUnitTemp.setText("°" + UnitPreferences.normalizeTempUnit(temp));
        if (btnUnitPres != null) btnUnitPres.setText(UnitPreferences.normalizePressureUnit(pres));
    }

    private void cycleDistanceUnit() {
        if (configStore == null) return;
        String next = UnitPreferences.DIST_MI.equals(configStore.getDistanceUnit())
                ? UnitPreferences.DIST_KM : UnitPreferences.DIST_MI;
        configStore.setDistanceUnit(next);
        updateUnitButtons();
        if (lastStatus != null) updateTelemetryUi(lastStatus);
        else VehicleWidgetProvider.refreshWidgets(this);
    }

    private void cycleTempUnit() {
        if (configStore == null) return;
        String next = UnitPreferences.TEMP_F.equals(configStore.getTempUnit())
                ? UnitPreferences.TEMP_C : UnitPreferences.TEMP_F;
        configStore.setTempUnit(next);
        updateUnitButtons();
        updateTargetTempLabel();
        if (lastStatus != null) updateTelemetryUi(lastStatus);
    }

    private void cyclePressureUnit() {
        if (configStore == null) return;
        String cur = UnitPreferences.normalizePressureUnit(configStore.getPressureUnit());
        String next = UnitPreferences.PRES_KPA.equals(cur)
                ? UnitPreferences.PRES_PSI
                : (UnitPreferences.PRES_PSI.equals(cur) ? UnitPreferences.PRES_BAR : UnitPreferences.PRES_KPA);
        configStore.setPressureUnit(next);
        updateUnitButtons();
        if (lastStatus != null) updateTelemetryUi(lastStatus);
    }

    private void toggleSyncIntervalUnit() {
        syncIntervalUnitHours = !syncIntervalUnitHours;
        if (etSyncInterval != null) {
            etSyncInterval.setError(null);
        }
        updateSyncIntervalUnitButton();
    }

    private void updateSyncIntervalUnitButton() {
        if (btnSyncIntervalUnit != null) {
            btnSyncIntervalUnit.setText(syncIntervalUnitHours ? R.string.unit_hours : R.string.unit_minutes);
        }
    }

    private void updateSyncIntervalUi(int totalMinutes) {
        updateSyncIntervalUnitButton();
        if (tvSyncIntervalLabel != null) {
            String summary = (totalMinutes >= 60 && totalMinutes % 60 == 0)
                    ? (totalMinutes / 60) + " " + getString(R.string.sync_unit_hr_short)
                    : totalMinutes + " " + getString(R.string.sync_unit_min_short);
            tvSyncIntervalLabel.setText(getString(R.string.label_widget_sync_freq) + " (" + summary + ")");
        }
    }

    private void saveWidgetSyncInterval() {
        if (configStore == null || etSyncInterval == null) return;
        String raw = etSyncInterval.getText() != null ? etSyncInterval.getText().toString().trim() : "";
        if (raw.isEmpty()) {
            etSyncInterval.setError("Enter a number");
            return;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            etSyncInterval.setError("Invalid number");
            return;
        }
        if (value < ConfigStore.MIN_WIDGET_SYNC_MINUTES) {
            etSyncInterval.setError("Must be >= 1");
            return;
        }
        int totalMinutes = syncIntervalUnitHours ? value * 60 : value;
        if (totalMinutes > ConfigStore.MAX_WIDGET_SYNC_MINUTES) {
            etSyncInterval.setError(syncIntervalUnitHours ? "Max 24 hr" : "Max 1440 min");
            return;
        }
        etSyncInterval.setError(null);
        configStore.setWidgetSyncIntervalMinutes(totalMinutes);
        WidgetSyncJobService.reschedulePeriodicSync(this);
        VehicleWidgetProvider.refreshWidgets(this);
        updateSyncIntervalUi(totalMinutes);
        String msg = "Widget auto-sync: every " + (syncIntervalUnitHours ? (value + " hr") : (totalMinutes + " min"));
        logMessage(msg);
        showToast(msg, false);
    }

    private int widgetStyleForButtonId(int viewId) {
        if (viewId == R.id.btnWidgetStyleRing) return ConfigStore.WIDGET_STYLE_RING;
        if (viewId == R.id.btnWidgetStyleBento) return ConfigStore.WIDGET_STYLE_BENTO;
        if (viewId == R.id.btnWidgetStyleTank) return ConfigStore.WIDGET_STYLE_TANK;
        return ConfigStore.WIDGET_STYLE_CAPSULE;
    }

    private String widgetStyleDescFor(int style) {
        switch (ConfigStore.normalizeWidgetStyle(style)) {
            case ConfigStore.WIDGET_STYLE_RING:
                return getString(R.string.widget_style_desc_ring);
            case ConfigStore.WIDGET_STYLE_BENTO:
                return getString(R.string.widget_style_desc_bento);
            case ConfigStore.WIDGET_STYLE_TANK:
                return getString(R.string.widget_style_desc_tank);
            case ConfigStore.WIDGET_STYLE_CAPSULE:
            default:
                return getString(R.string.widget_style_desc_capsule);
        }
    }

    private String appLanguageForButtonId(int viewId) {
        if (viewId == R.id.btnLangEnglish) return ConfigStore.LANG_EN;
        if (viewId == R.id.btnLangIndonesian) return ConfigStore.LANG_ID;
        return ConfigStore.LANG_SYSTEM;
    }

    private String appLanguageDisplayLabel(Context ctx, String lang) {
        String normalized = ConfigStore.normalizeAppLanguage(lang);
        if (ConfigStore.LANG_EN.equals(normalized)) return ctx.getString(R.string.lang_option_english);
        if (ConfigStore.LANG_ID.equals(normalized)) return ctx.getString(R.string.lang_option_indonesian);
        return ctx.getString(R.string.lang_option_system);
    }

    private void updateAppLanguageUi(String language) {
        if (toggleAppLanguage == null) return;
        String normalized = ConfigStore.normalizeAppLanguage(language);
        int btnId = R.id.btnLangSystem;
        if (ConfigStore.LANG_EN.equals(normalized)) btnId = R.id.btnLangEnglish;
        else if (ConfigStore.LANG_ID.equals(normalized)) btnId = R.id.btnLangIndonesian;
        updatingAppLanguageUi = true;
        toggleAppLanguage.check(btnId);
        updatingAppLanguageUi = false;
    }

    private void updateWidgetStyleUi(int style) {
        int normalized = ConfigStore.normalizeWidgetStyle(style);
        if (toggleWidgetStyle != null) {
            int btnId = R.id.btnWidgetStyleCapsule;
            if (normalized == ConfigStore.WIDGET_STYLE_RING) btnId = R.id.btnWidgetStyleRing;
            else if (normalized == ConfigStore.WIDGET_STYLE_BENTO) btnId = R.id.btnWidgetStyleBento;
            else if (normalized == ConfigStore.WIDGET_STYLE_TANK) btnId = R.id.btnWidgetStyleTank;
            updatingWidgetStyleUi = true;
            toggleWidgetStyle.check(btnId);
            updatingWidgetStyleUi = false;
        }
        if (tvWidgetStyleDesc != null) {
            tvWidgetStyleDesc.setText(widgetStyleDescFor(normalized));
        }
    }

    private void saveCustomModelName() {
        if (configStore == null || etCustomModelName == null) return;
        String custom = etCustomModelName.getText() != null
                ? etCustomModelName.getText().toString().trim().replaceAll("\\s+", " ")
                : "";
        configStore.setCustomVehicleName(custom);
        etCustomModelName.setText(custom);
        etCustomModelName.clearFocus();
        updateHeaderVehicleInfo(lastStatus);
        VehicleWidgetProvider.refreshWidgets(this);
        LocationWidgetProvider.refreshWidgets(this);
        String msg = custom.isEmpty()
                ? "Car model reset to auto-detect"
                : ("Car model set to " + custom);
        logMessage(msg);
        showToast(msg, false);
    }

    private void updateHeaderVehicleInfo(VehicleStatus status) {
        String customModel = configStore != null ? configStore.getCustomVehicleName() : "";
        String displayModel = (customModel != null && !customModel.trim().isEmpty())
                ? customModel.trim()
                : VehicleSummary.formatDisplayModel(resolveDisplayModelName(status));
        if (tvHeaderTitle != null) {
            tvHeaderTitle.setText(displayModel);
        }
        updateHeaderSubtitleForPage(currentPageId);
        if (tvDrawerCarModel != null) {
            tvDrawerCarModel.setText(displayModel);
        }
        if (tvControlsTitle != null) {
            tvControlsTitle.setText(getString(R.string.controls_title_format, displayModel));
        }
    }

    private String resolveDisplayModelName(VehicleStatus status) {
        String activeVin = (status != null && status.vin != null && !status.vin.trim().isEmpty())
                ? status.vin.trim()
                : (configStore != null && configStore.getVin() != null ? configStore.getVin().trim() : "");
        List<VehicleSummary> list = (status != null && status.vehicles != null && !status.vehicles.isEmpty())
                ? status.vehicles
                : (configStore != null ? configStore.getVehicles() : null);
        if (!activeVin.isEmpty() && list != null) {
            for (VehicleSummary v : list) {
                if (v != null && activeVin.equals(v.vin) && !VehicleSummary.isGenericModelName(v.modelName)) {
                    return v.modelName;
                }
            }
        }
        if (configStore != null && !VehicleSummary.isGenericModelName(configStore.getVehicleName())) {
            return configStore.getVehicleName().trim();
        }
        if (status != null && !VehicleSummary.isGenericModelName(status.modelName)) {
            return status.modelName.trim();
        }
        return VehicleSummary.DEFAULT_MODEL_NAME;
    }

    private void updateMultiCarBar(VehicleStatus status) {
        if (layoutMultiCarBar == null || btnSwitchActiveCar == null) return;
        List<VehicleSummary> list = status != null && status.vehicles != null && !status.vehicles.isEmpty()
                ? status.vehicles
                : (configStore != null ? configStore.getVehicles() : null);
        if (list != null && list.size() > 1) {
            layoutMultiCarBar.setVisibility(View.VISIBLE);
            String activeVin = status != null && status.vin != null && !status.vin.isEmpty()
                    ? status.vin
                    : (configStore != null ? configStore.getVin() : "");
            btnSwitchActiveCar.setText(getString(R.string.btn_switch_car, list.size(), maskVin(activeVin)));
        } else {
            layoutMultiCarBar.setVisibility(View.GONE);
        }
    }

    private void showMultiCarPicker() {
        List<VehicleSummary> list = lastStatus != null && lastStatus.vehicles != null && !lastStatus.vehicles.isEmpty()
                ? lastStatus.vehicles
                : (configStore != null ? configStore.getVehicles() : null);
        if (list == null || list.isEmpty()) {
            showToast("Fetch vehicles first to populate garage list", false);
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            VehicleSummary v = list.get(i);
            labels[i] = v.watchShortLabel() + " (" + maskVin(v.vin) + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_switch_vehicle_title)
                .setItems(labels, (dialog, which) -> {
                    VehicleSummary picked = list.get(which);
                    executeWatchAction(WatchProtocol.ACTION_SWITCH_CAR, -1, picked.vin, -1);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateWatchTargetLabel() {
        if (btnWatchTargetToggle == null) return;
        String target = configStore != null ? configStore.getWatchTarget() : "dev";
        boolean store = "store".equalsIgnoreCase(target);
        btnWatchTargetToggle.setText(store ? "Store (com.evwatch.bridge)" : "Dev (com.bydwatch.gt4)");
    }

    private void toggleWatchTarget() {
        if (configStore == null) return;
        String next = "store".equalsIgnoreCase(configStore.getWatchTarget()) ? "dev" : "store";
        configStore.setWatchTarget(next);
        BydWatchApp app = (BydWatchApp) getApplication();
        if (app == null) app = BydWatchApp.getInstance();
        if (app != null) {
            app.refreshWatchTarget();
        }
        updateWatchTargetLabel();
        logMessage("Watch target switched to " + next + (WearBridgeService.isRunning()
                ? ". Restart Bridge Service to apply." : "."));
        showToast("Watch target: " + next, false);
    }

    private void updateReadinessChecklist() {
        boolean accountOk = configStore != null && configStore.isConfigured();
        boolean healthOk = bridge != null
                && bridge.getConnectionState() != WearEngineBridge.ConnectionState.PERMISSION_NEEDED;
        boolean batteryOk = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } else {
            batteryOk = true;
        }
        applyCheckPill(tvCheckAccount, "1. Account", accountOk);
        applyCheckPill(tvCheckHealth, "2. Health Auth", healthOk);
        applyCheckPill(tvCheckBattery, "3. Battery", batteryOk);
    }

    private void applyCheckPill(TextView pill, String label, boolean ok) {
        if (pill == null) return;
        pill.setText((ok ? "OK · " : "TODO · ") + label);
        pill.setTextColor(ContextCompat.getColor(this, ok ? R.color.accent_emerald : R.color.accent_amber));
    }

    private void showDisclaimerDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_body)
                .setNeutralButton(R.string.disclaimer_github,
                        (d, w) -> openExternalUrl(getString(R.string.about_github_companion_url)))
                .setPositiveButton(R.string.disclaimer_close, null)
                .show();
    }

    private void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())));
        } catch (Exception e) {
            showToast("Unable to open link", true);
        }
    }

    private void copyEventLog() {
        if (tvStatusLog == null) return;
        String text = tvStatusLog.getText() != null ? tvStatusLog.getText().toString() : "";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("BYD Watch Log", text);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PersistableBundle extras = new PersistableBundle();
                String key = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? ClipDescription.EXTRA_IS_SENSITIVE
                        : "android.content.extra.IS_SENSITIVE";
                extras.putBoolean(key, true);
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
            showToast(getString(R.string.log_copied_toast), false);
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
        if (scrollStatusLog != null && tvStatusLog != null) {
            scrollStatusLog.post(() -> {
                int overflow = tvStatusLog.getBottom()
                        - (scrollStatusLog.getHeight() - scrollStatusLog.getPaddingBottom());
                if (overflow > 0) {
                    scrollStatusLog.scrollTo(0, overflow);
                }
            });
        }
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
