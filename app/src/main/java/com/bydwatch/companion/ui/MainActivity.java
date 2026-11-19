package com.bydwatch.companion.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.core.widget.NestedScrollView;
import android.widget.HorizontalScrollView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.SecureConfigStore;
import com.bydwatch.companion.model.CustomActionCombo;
import com.bydwatch.companion.model.RegionOption;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.model.WidgetSlotConfig;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;
import com.bydwatch.companion.update.AppUpdateChecker;
import com.bydwatch.companion.update.AppUpdateInfo;
import com.bydwatch.companion.wear.WearBridgeService;
import com.bydwatch.companion.wear.WearEngineBridge;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Android Activity for BYD login, VIN fetch, live telemetry, and vehicle control.
 */
public class MainActivity extends AppCompatActivity {

    private static final AtomicLong CMD_SEQ = new AtomicLong();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat garageTimeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private ConfigStore configStore;
    private BydGateway gateway;
    private BydRepository repository;
    private WearEngineBridge bridge;
    private WearEngineBridge.StateListener stateListener;
    private WearBridgeService.ServiceStateListener serviceStateListener;

    private static final int RC_PERMISSIONS = 1001;
    private static final long CLIMATE_DEBOUNCE_MS = 700L;
    private static final float DISABLED_ACTION_ALPHA = 0.45f;
    private static final String STATE_CURRENT_PAGE_ID = "state_current_page_id";

    private int restoredPageId = 0;
    private boolean actionInFlight = false;
    private final Set<View> lockedDynamicControls = Collections.newSetFromMap(new WeakHashMap<>());

    private int targetTempC = WatchProtocol.DEFAULT_TEMP_C;
    private int targetTempGear = ConfigStore.DEFAULT_TEMP_GEAR;
    private int targetFanLevel = ConfigStore.DEFAULT_FAN_LEVEL;
    private int targetWindMode = ConfigStore.DEFAULT_WIND_MODE;
    private int targetAcDurationMin = ConfigStore.DEFAULT_AC_DURATION_MINUTES;
    private boolean updatingManualAcSwitch = false;
    private boolean syncIntervalUnitHours = false;
    private VehicleStatus lastStatus;
    private final Handler climateDebounceHandler = new Handler(Looper.getMainLooper());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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

    private SwipeRefreshLayout swipeRefresh;
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
    private EditText etBatteryCapacityKwh;
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
    private MaterialButton btnPopupAcTimer10;
    private MaterialButton btnPopupAcTimer15;
    private MaterialButton btnPopupAcTimer20;
    private MaterialButton btnPopupAcTimer25;
    private MaterialButton btnPopupAcTimer30;
    private Button btnPopupApply;
    private Button btnLights;
    private Button btnHorn;
    private View cardClimateControl;
    private View layoutAlertControls;
    private View layoutComfortRow1;
    private View layoutComfortRow2;
    private View layoutHealthPanel;
    private Button btnEditHomeLayout;
    private Button btnPrefsEditHomeLayout;
    private Button btnVentWindows;
    private Button btnCloseWindows;
    private Button btnBatHeat;
    private Button btnSeatHeat;
    private View layoutVehicleCarousel;
    private HorizontalScrollView scrollVehicle;
    private LinearLayout layoutVehicleCards;
    private LinearLayout layoutVehicleDots;
    private TextView tvVehicleSwipeHint;
    private boolean vehicleCarouselHasCars;
    private String switchingToName;
    private String switchPreviousVin;
    private String switchTargetVin;
    private int vehicleCardWidthPx;
    private String vehicleCarouselKey;
    private float vehicleDownX;
    private float vehicleDownY;
    private int vehicleDragStartIndex;
    private long lastVehicleSwipeAt;
    private android.view.VelocityTracker vehicleVelocityTracker;
    private final LruCache<String, Bitmap> vehicleIconCache = new LruCache<>(12);
    private BottomSheetDialog editVehicleDialog;
    private String editVehicleVin;
    private String editVehicleEmoji;
    private String editVehiclePhoto;
    private boolean editVehicleUsePhoto;
    private View editVehicleSheet;
    private ActivityResultLauncher<String[]> vehiclePhotoPicker;
    private View cardGarage;
    private TextView tvGarageCount;
    private TextView tvGarageMeta;
    private TextView tvGarageEmptyHint;
    private LinearLayout layoutGarageRows;
    private Button btnRefreshGarage;
    private boolean garageHasContent;
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
    private MaterialButtonToggleGroup toggleWidgetStyle;
    private TextView tvWidgetStyleDesc;
    private WidgetComboUiHelper widgetComboUiHelper;
    private HomeLayoutEditorHelper homeLayoutEditorHelper;
    private LinearLayout layoutCombosContainer;
    private LinearLayout layoutHomeCustomGrid;
    private boolean updatingWidgetStyleUi = false;
    private MaterialButtonToggleGroup toggleAppLanguage;
    private boolean updatingAppLanguageUi = false;
    private boolean vinVerifiedByFetch = false;
    private boolean updatingAccountFields = false;
    private boolean updatingHideExtraSwitch = false;
    private SwitchCompat switchManualAc;
    private SwitchCompat switchHideExtraControls;
    private SwitchCompat switchSmartAlerts;
    private SwitchCompat switchSingleFlightAction;
    private SwitchCompat switchVerboseLogs;
    private MaterialButtonToggleGroup toggleBiometricScope;
    private boolean updatingBiometricScopeUi = false;
    private View layoutBiometricOptions;
    private EditText etBiometricGraceMinutes;
    private Button btnApplyBiometricGrace;
    private TextView btnToggleBiometricAdvanced;
    private View layoutBiometricAdvanced;
    private SwitchCompat switchBiometricWidgetSync;
    private boolean updatingBiometricWidgetSyncUi = false;
    private boolean biometricAdvancedExpanded = false;
    private View layoutAppLockOverlay;
    private Button btnUnlockApp;
    private boolean appUnlockedInCurrentForeground = false;
    private boolean biometricPromptShowing = false;
    private Button btnDisclaimer;
    private Button btnAuthHealth;
    private Button btnToggleService;
    private Button btnBatteryOpt;
    private Button btnCopyLog;
    private Button btnClearLog;
    private Button btnAboutGithubCompanion;
    private Button btnAboutGithubWatch;
    private Button btnAboutSupport;
    private View cardPrefSupport;
    private ImageButton btnDismissPrefSupport;
    private Button btnPrefSupport;
    private Button btnCheckAppUpdate;
    private TextView tvAboutVersion;
    private TextView tvAppUpdateStatus;
    private View bannerAppUpdate;
    private TextView tvUpdateBannerTitle;
    private Button btnUpdateBannerAction;
    private AppUpdateUiHelper updateUiHelper;
    private AppUpdateInfo availableUpdateInfo;
    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (updateUiHelper != null) {
                    updateUiHelper.onInstallPermissionResult();
                }
            });
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
        if ((com.bydwatch.companion.BuildConfig.DEBUG || com.bydwatch.companion.BuildConfig.DEMO_BUILD)
                && getIntent() != null && getIntent().hasExtra("demo_lang")) {
            configStore.setAppLanguage(getIntent().getStringExtra("demo_lang"));
        }
        AppLocaleHelper.applyLanguage(configStore.getAppLanguage());
        gateway = app.getGateway();
        repository = app.getRepository();
        bridge = app.getWearEngineBridge();

        bindViews();
        vehiclePhotoPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null || editVehicleVin == null) return;
                    String path = VehicleIconStore.savePhoto(this, editVehicleVin, uri);
                    if (path.isEmpty()) {
                        showToast(getString(R.string.edit_icon_photo_hint), false);
                        return;
                    }
                    editVehiclePhoto = path;
                    editVehicleUsePhoto = true;
                    editVehicleEmoji = "";
                    bindEditVehiclePreview();
                    if (editVehicleSheet != null) {
                        MaterialButtonToggleGroup group = editVehicleSheet.findViewById(R.id.toggleEditIconMode);
                        if (group != null) group.check(R.id.btnEditIconPhoto);
                    }
                });
        initValues();
        migrateLegacyVehicleName();
        setupListeners();
        setupBackNavigation();

        if (bridge != null) {
            stateListener = state -> runOnUiThread(this::updateWatchStatus);
            bridge.addStateListener(stateListener);
        }

        serviceStateListener = running -> runOnUiThread(this::updateWatchStatus);
        WearBridgeService.addStateListener(serviceStateListener);

        initAppUpdater(app);
        enforceAppLockIfNeeded(false);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_PAGE_ID, currentPageId);
    }

    private final BydRepository.StatusListener backgroundStatusListener =
            status -> runOnUiThread(() -> updateTelemetryUi(status));

    @Override
    protected void onStart() {
        super.onStart();
        if (repository != null) {
            repository.addStatusListener(backgroundStatusListener);
        }
        enforceAppLockIfNeeded(true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (repository != null) {
            repository.removeStatusListener(backgroundStatusListener);
        }
        if (!isChangingConfigurations() && !biometricPromptShowing) {
            appUnlockedInCurrentForeground = false;
        }
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
        if (updateUiHelper != null) {
            updateUiHelper.destroy();
            updateUiHelper = null;
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

        swipeRefresh = findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) {
            int primary = MaterialColors.getColor(swipeRefresh,
                    com.google.android.material.R.attr.colorPrimary,
                    ContextCompat.getColor(this, R.color.accent_emerald));
            int secondary = MaterialColors.getColor(swipeRefresh,
                    com.google.android.material.R.attr.colorSecondary,
                    ContextCompat.getColor(this, R.color.accent_cyan));
            int surfaceBg = MaterialColors.getColor(swipeRefresh,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    ContextCompat.getColor(this, R.color.surface_card));
            swipeRefresh.setColorSchemeColors(primary, secondary);
            swipeRefresh.setProgressBackgroundColorSchemeColor(surfaceBg);
            swipeRefresh.setOnRefreshListener(() -> executeWatchAction(WatchProtocol.ACTION_STATUS, -1));
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
        etBatteryCapacityKwh = findViewById(R.id.etBatteryCapacityKwh);
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
        cardClimateControl = findViewById(R.id.cardClimateControl);
        layoutAlertControls = findViewById(R.id.layoutAlertControls);
        layoutComfortRow1 = findViewById(R.id.layoutComfortRow1);
        layoutComfortRow2 = findViewById(R.id.layoutComfortRow2);
        layoutHealthPanel = findViewById(R.id.layoutHealthPanel);
        btnEditHomeLayout = findViewById(R.id.btnEditHomeLayout);
        btnPrefsEditHomeLayout = findViewById(R.id.btnPrefsEditHomeLayout);
        btnVentWindows = findViewById(R.id.btnVentWindows);
        btnCloseWindows = findViewById(R.id.btnCloseWindows);
        btnBatHeat = findViewById(R.id.btnBatHeat);
        btnSeatHeat = findViewById(R.id.btnSeatHeat);
        layoutVehicleCarousel = findViewById(R.id.layoutVehicleCarousel);
        scrollVehicle = findViewById(R.id.scrollVehicle);
        layoutVehicleCards = findViewById(R.id.layoutVehicleCards);
        layoutVehicleDots = findViewById(R.id.layoutVehicleDots);
        tvVehicleSwipeHint = findViewById(R.id.tvVehicleSwipeHint);
        cardGarage = findViewById(R.id.cardGarage);
        tvGarageCount = findViewById(R.id.tvGarageCount);
        tvGarageMeta = findViewById(R.id.tvGarageMeta);
        tvGarageEmptyHint = findViewById(R.id.tvGarageEmptyHint);
        layoutGarageRows = findViewById(R.id.layoutGarageRows);
        btnRefreshGarage = findViewById(R.id.btnRefreshGarage);
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
        toggleWidgetStyle = findViewById(R.id.toggleWidgetStyle);
        tvWidgetStyleDesc = findViewById(R.id.tvWidgetStyleDesc);
        widgetComboUiHelper = new WidgetComboUiHelper(
                this,
                configStore,
                new WidgetComboUiHelper.Listener() {
                    @Override
                    public VehicleStatus getCurrentStatus() {
                        return lastStatus;
                    }

                    @Override
                    public void onRunCombo(CustomActionCombo combo) {
                        runComboFromUi(combo);
                    }

                    @Override
                    public void onConfigUpdated() {
                        VehicleWidgetProvider.refreshWidgets(MainActivity.this);
                    }
                }
        );
        layoutCombosContainer = findViewById(R.id.layoutCombosContainer);
        widgetComboUiHelper.bindViews(
                findViewById(R.id.flWidgetPreviewContainer),
                findViewById(R.id.tvWidgetPreviewSampleBadge),
                new MaterialButton[]{
                        findViewById(R.id.btnWidgetSlot1),
                        findViewById(R.id.btnWidgetSlot2),
                        findViewById(R.id.btnWidgetSlot3),
                        findViewById(R.id.btnWidgetSlot4)
                },
                findViewById(R.id.btnResetWidgetSlots),
                findViewById(R.id.btnNewActionCombo),
                layoutCombosContainer
        );
        homeLayoutEditorHelper = new HomeLayoutEditorHelper(
                this,
                configStore,
                widgetComboUiHelper,
                new HomeLayoutEditorHelper.Listener() {
                    @Override
                    public VehicleStatus getCurrentStatus() {
                        return lastStatus;
                    }

                    @Override
                    public void onRunSingleAction(String actionKey) {
                        dispatchSingleHomeAction(actionKey);
                    }

                    @Override
                    public void onRunCombo(CustomActionCombo combo) {
                        runComboFromUi(combo);
                    }

                    @Override
                    public void onLayoutConfigChanged() {
                        updateHomeSectionsVisibility();
                    }
                }
        );
        layoutHomeCustomGrid = findViewById(R.id.layoutHomeCustomGrid);
        homeLayoutEditorHelper.bindViews(
                findViewById(R.id.layoutHomeCustomSection),
                layoutHomeCustomGrid
        );
        toggleAppLanguage = findViewById(R.id.toggleAppLanguage);
        switchManualAc = findViewById(R.id.switchManualAc);
        switchHideExtraControls = findViewById(R.id.switchHideExtraControls);
        switchSmartAlerts = findViewById(R.id.switchSmartAlerts);
        switchSingleFlightAction = findViewById(R.id.switchSingleFlightAction);
        switchVerboseLogs = findViewById(R.id.switchVerboseLogs);
        toggleBiometricScope = findViewById(R.id.toggleBiometricScope);
        layoutBiometricOptions = findViewById(R.id.layoutBiometricOptions);
        etBiometricGraceMinutes = findViewById(R.id.etBiometricGraceMinutes);
        btnApplyBiometricGrace = findViewById(R.id.btnApplyBiometricGrace);
        btnToggleBiometricAdvanced = findViewById(R.id.btnToggleBiometricAdvanced);
        layoutBiometricAdvanced = findViewById(R.id.layoutBiometricAdvanced);
        switchBiometricWidgetSync = findViewById(R.id.switchBiometricWidgetSync);
        layoutAppLockOverlay = findViewById(R.id.layoutAppLockOverlay);
        btnUnlockApp = findViewById(R.id.btnUnlockApp);
        btnDisclaimer = findViewById(R.id.btnDisclaimer);
        btnAuthHealth = findViewById(R.id.btnAuthHealth);
        btnToggleService = findViewById(R.id.btnToggleService);
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnAboutGithubCompanion = findViewById(R.id.btnAboutGithubCompanion);
        btnAboutGithubWatch = findViewById(R.id.btnAboutGithubWatch);
        btnAboutSupport = findViewById(R.id.btnAboutSupport);
        cardPrefSupport = findViewById(R.id.cardPrefSupport);
        btnDismissPrefSupport = findViewById(R.id.btnDismissPrefSupport);
        btnPrefSupport = findViewById(R.id.btnPrefSupport);
        btnCheckAppUpdate = findViewById(R.id.btnCheckAppUpdate);
        tvAboutVersion = findViewById(R.id.tvAboutVersion);
        tvAppUpdateStatus = findViewById(R.id.tvAppUpdateStatus);
        bannerAppUpdate = findViewById(R.id.bannerAppUpdate);
        tvUpdateBannerTitle = findViewById(R.id.tvUpdateBannerTitle);
        btnUpdateBannerAction = findViewById(R.id.btnUpdateBannerAction);
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
        updatingAccountFields = true;
        try {
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

                if (etBatteryCapacityKwh != null) {
                    float kwh = configStore.getBatteryCapacityKwh();
                    etBatteryCapacityKwh.setText(kwh > 0f ? formatKwhInput(kwh) : "");
                }
            }
        } finally {
            updatingAccountFields = false;
        }

        vinVerifiedByFetch = configStore != null && configStore.isConfigured();
        updateSaveCredentialsButtonState();
        attachAccountCredentialWatchers();

        if (configStore != null) {
            targetTempC = configStore.getDefaultTempC();
            if (targetTempC < WatchProtocol.MIN_TEMP_C || targetTempC > WatchProtocol.MAX_TEMP_C) {
                targetTempC = WatchProtocol.DEFAULT_TEMP_C;
            }
            targetTempGear = configStore.getDefaultTempGear();
            targetFanLevel = configStore.getDefaultFanLevel();
            targetWindMode = configStore.getDefaultWindMode();
            targetAcDurationMin = configStore.getDefaultAcDurationMinutes();
            if (switchManualAc != null) {
                updatingManualAcSwitch = true;
                switchManualAc.setChecked(configStore.isManualAcMode());
                updatingManualAcSwitch = false;
            }
            if (switchSmartAlerts != null) {
                switchSmartAlerts.setChecked(configStore.isAlertsEnabled());
            }
            if (switchSingleFlightAction != null) {
                switchSingleFlightAction.setChecked(configStore.isSingleFlightActionEnabled());
            }
            if (switchVerboseLogs != null) {
                switchVerboseLogs.setChecked(configStore.isVerboseLogsEnabled());
            }
            updateBiometricSettingsUi();
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
            updateWidgetStyleUi(configStore.getWidgetStyle());
            updateAppLanguageUi(configStore.getAppLanguage());
        }
        updateClimateControlsUi();
        updateUnitButtons();
        updateWatchTargetLabel();
        updateHeaderVehicleInfo(null);

        if (repository != null && repository.getCachedStatus() != null) {
            updateTelemetryUi(repository.getCachedStatus());
        }

        boolean configured = configStore != null && configStore.isConfigured();
        setAccountExpanded(!configured);
        setSetupGuideExpanded(!configured);
        updateAccountUiState();
        updateWatchStatus();
        if (tvAboutVersion != null) {
            tvAboutVersion.setText(getString(
                    R.string.about_installed_version_fmt,
                    com.bydwatch.companion.BuildConfig.VERSION_NAME));
        }
        if (cardPrefSupport != null) {
            boolean dismissed = configStore != null && configStore.isSupportCardDismissed();
            cardPrefSupport.setVisibility(dismissed ? View.GONE : View.VISIBLE);
        }
        int initialPage = restoredPageId != 0
                ? restoredPageId
                : (configured ? R.id.nav_controls : R.id.nav_account);
        showPage(initialPage);

        if (isDemoFlavor() || (com.bydwatch.companion.BuildConfig.DEBUG && getIntent() != null
                && getIntent().getBooleanExtra("demo_mode", false))) {
            applyDebugDemoState(getIntent() != null ? getIntent() : new Intent());
        }
    }

    private static boolean isDemoFlavor() {
        return com.bydwatch.companion.BuildConfig.DEMO_BUILD;
    }

    /** Random battery and charge state for demo cars, keeping the other fields intact. */
    private static VehicleStatus randomizeDemoStatus(VehicleStatus base, String vin, String model,
                                                     java.util.Random random) {
        if (base == null) return null;
        int soc = 38 + random.nextInt(58);                    // 38..95 %
        float kmPerPercent = 3.0f + random.nextFloat() * 0.8f;
        int rangeKm = Math.max(40, Math.round(soc * kmPerPercent));
        int roll = random.nextInt(3);
        int chargingState = roll == 0
                ? VehicleStatus.CHARGING_ACTIVE
                : (roll == 1 ? VehicleStatus.CHARGING_CONNECTED : VehicleStatus.CHARGING_NONE);
        Double powerKw = chargingState == VehicleStatus.CHARGING_ACTIVE
                ? (random.nextBoolean() ? 7.2 : 11.0)
                : null;
        Integer remaining = chargingState == VehicleStatus.CHARGING_ACTIVE
                ? 25 + random.nextInt(150)
                : null;
        return base.toBuilder()
                .vin(vin)
                .modelName(model)
                .soc(soc)
                .rangeKm(rangeKm)
                .chargingState(chargingState)
                .chargePowerKw(powerKw)
                .chargeRemainingMinutes(remaining)
                .ts(System.currentTimeMillis() / 1000L)
                .build();
    }

    private void applyDebugDemoState(Intent intent) {
        if (!com.bydwatch.companion.BuildConfig.DEBUG && !isDemoFlavor()) {
            return;
        }
        if (configStore == null) {
            return;
        }
        configStore.setRegion("id");
        configStore.setUsername("demo.driver@example.com");
        configStore.setPassword("DemoPass123!");
        configStore.setPin("000000");
        configStore.setVin("LGX0DEMO000000001");
        configStore.setVehicleName("BYD Atto 1");
        // No custom name override: the header must follow the car the switcher selects.
        configStore.setCustomVehicleName("");
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

        if (isDemoFlavor()) {
            final String demoVin = demo.vin;
            String primaryName = demo.modelName;
            final String secondVin = "LGX0DEMO000000002";
            // The second car gets its own plausible numbers, so a screenshot of the garage does
            // not show two different cars with identical telemetry.
            VehicleStatus second = new VehicleStatus.Builder(64, 352, true, false, 24, 22, System.currentTimeMillis() / 1000L)
                    .vin(secondVin)
                    .modelName("BYD Seal")
                    .chargingState(VehicleStatus.CHARGING_NONE)
                    .totalMileageKm(8310)
                    .outsideTempC(31)
                    .tirePressuresKpa(250, 250, 248, 248)
                    .capabilities(true, true, true)
                    .location(-6.22460, 106.80850, System.currentTimeMillis() / 1000L)
                    .build();

            java.util.Random demoRandom = new java.util.Random();
            demo = randomizeDemoStatus(demo, "LGX0DEMO000000001", "BYD Atto 1", demoRandom);
            second = randomizeDemoStatus(second, "LGX0DEMO000000002", "BYD Seal", demoRandom);
            // Demo default is a two-car garage so the "Switch Car" control is visible without
            // any adb extras. Pass --es demo_scenario single to show the one-car layout instead.
            if (!"single".equalsIgnoreCase(intent.getStringExtra("demo_scenario"))) {
                List<VehicleSummary> garage = new ArrayList<>();
                garage.add(new VehicleSummary(demoVin, primaryName, "", "EV"));
                garage.add(new VehicleSummary(secondVin, "BYD Seal", "", "EV"));
                configStore.setVehicles(Collections.unmodifiableList(garage));
                demo = demo.toBuilder().vehicles(garage).build();
                second = second.toBuilder().vehicles(garage).build();
                java.util.Map<String, VehicleStatus> fleet = new java.util.HashMap<>();
                fleet.put(demoVin, demo);
                fleet.put(secondVin, second);
                repository.setDemoFleet(fleet);
            } else {
                List<VehicleSummary> solo = new ArrayList<>();
                solo.add(new VehicleSummary(demoVin, primaryName, "", "EV"));
                configStore.setVehicles(Collections.unmodifiableList(solo));
                demo = demo.toBuilder().vehicles(solo).build();
                repository.setDemoFleet(null);
            }
            final VehicleStatus seeded = demo;
            // Demo builds have no account, so keep the sample car from being cleared or replaced.
            repository.setDemoStatusProvider(() -> {
                VehicleStatus current = repository.getCachedStatus();
                return current != null && current.vin != null && !current.vin.isEmpty()
                        ? current
                        : seeded;
            });
        }

        if (repository != null) {
            repository.setCachedStatus(demo);
        }
        VehicleWidgetProvider.recordSuccessfulSync(this, demo);
        VehicleWidgetProvider.refreshWidgets(this);

        updatingAccountFields = true;
        try {
            if (etUsername != null) etUsername.setText("demo.driver@example.com");
            if (etVin != null) etVin.setText("LGX0DEMO000000001");
        } finally {
            updatingAccountFields = false;
        }
        vinVerifiedByFetch = true;
        updateSaveCredentialsButtonState();
        updateAccountUiState();
        updateWidgetStyleUi(configStore.getWidgetStyle());
        updateAppLanguageUi(configStore.getAppLanguage());
        updateTelemetryUi(demo);
        updateHeaderVehicleInfo(demo);
        updateClimateControlsUi();
        updateHomeSectionsVisibility();
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
        renderGarageCard();
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

    void showPage(int navItemId) {
        currentPageId = navItemId;
        if (cardControls != null) {
            cardControls.setVisibility(navItemId == R.id.nav_controls ? View.VISIBLE : View.GONE);
        }
        if (layoutVehicleCarousel != null) {
            layoutVehicleCarousel.setVisibility(navItemId == R.id.nav_controls && vehicleCarouselHasCars
                    ? View.VISIBLE : View.GONE);
        }
        if (cardAccount != null) {
            cardAccount.setVisibility(navItemId == R.id.nav_account ? View.VISIBLE : View.GONE);
        }
        if (cardGarage != null) {
            cardGarage.setVisibility(navItemId == R.id.nav_account && garageHasContent
                    ? View.VISIBLE : View.GONE);
        }
        if (cardPreferences != null) {
            cardPreferences.setVisibility(navItemId == R.id.nav_preferences ? View.VISIBLE : View.GONE);
        }
        if (cardBridge != null) {
            cardBridge.setVisibility(navItemId == R.id.nav_watch_bridge ? View.VISIBLE : View.GONE);
        }
        updateAppBannerVisibility();
        if (navView != null) {
            navView.setCheckedItem(navItemId);
        }
        updateHeaderSubtitleForPage(navItemId);
        if (swipeRefresh != null) {
            boolean onControls = (navItemId == R.id.nav_controls);
            swipeRefresh.setEnabled(onControls && !actionInFlight);
            if (!onControls) {
                stopSwipeRefreshing();
            }
        }
        if (rootScroll != null) {
            rootScroll.scrollTo(0, 0);
        }
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
    }

    private void updateHeaderSubtitleForPage(int navItemId) {
        if (tvHeaderSubtitle == null) return;
        if (navItemId == R.id.nav_account) {
            tvHeaderSubtitle.setText(getString(R.string.card_account_title));
        } else if (navItemId == R.id.nav_preferences) {
            tvHeaderSubtitle.setText(getString(R.string.card_preferences_title));
        } else if (navItemId == R.id.nav_watch_bridge) {
            tvHeaderSubtitle.setText(getString(R.string.nav_watch_bridge));
        } else {
            tvHeaderSubtitle.setText(getString(R.string.nav_controls));
        }
    }

    private void setupListeners() {
        if (btnHamburger != null && drawerLayout != null) {
            btnHamburger.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }
        if (navView != null) {
            navView.setNavigationItemSelectedListener(item -> {
                if (item.getItemId() == R.id.nav_check_update) {
                    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    }
                    if (updateUiHelper != null) {
                        updateUiHelper.checkForUpdates(true);
                    }
                    return false;
                }
                if (item.getItemId() == R.id.nav_support) {
                    if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    }
                    openExternalUrl(getString(R.string.support_url));
                    return false;
                }
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
        if (btnEditHomeLayout != null) {
            btnEditHomeLayout.setOnClickListener(v -> openHomeLayoutEditor());
        }
        if (btnPrefsEditHomeLayout != null) {
            btnPrefsEditHomeLayout.setOnClickListener(v -> openHomeLayoutEditor());
        }

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
            btnBatHeat.setOnClickListener(v -> dispatchSingleHomeAction(WidgetSlotConfig.SINGLE_BAT_HEAT));
        }
        if (btnSeatHeat != null) {
            btnSeatHeat.setOnClickListener(v -> dispatchSingleHomeAction(WidgetSlotConfig.SINGLE_SEAT_HEAT));
        }
        if (btnRefreshGarage != null) {
            btnRefreshGarage.setOnClickListener(v -> executeWatchAction(WatchProtocol.ACTION_STATUS, -1));
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
        if (toggleAppLanguage != null) {
            toggleAppLanguage.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked || updatingAppLanguageUi) return;
                String lang = appLanguageForButtonId(checkedId);
                if (configStore != null) {
                    configStore.setAppLanguage(lang);
                }
                updateAppLanguageUi(lang);
                if (widgetComboUiHelper != null) widgetComboUiHelper.refreshUi();
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
                logMessage("Widget style set to " + desc);
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
                if (updatingHideExtraSwitch) return;
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
        if (switchSingleFlightAction != null) {
            switchSingleFlightAction.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (configStore != null) {
                    configStore.setSingleFlightActionEnabled(isChecked);
                    if (!isChecked && actionInFlight) {
                        setVehicleActionInFlight(false);
                    }
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
        if (toggleBiometricScope != null) {
            toggleBiometricScope.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked || updatingBiometricScopeUi || configStore == null) {
                    return;
                }
                String newScope = biometricScopeFromButtonId(checkedId);
                if (!ConfigStore.BIOMETRIC_SCOPE_OFF.equals(newScope)
                        && !BiometricLockHelper.isDeviceCredentialAvailable(this)) {
                    showToast(getString(R.string.biometric_unavailable_toast), true);
                    updateBiometricSettingsUi();
                    return;
                }
                configStore.setBiometricScope(newScope);
                if (ConfigStore.BIOMETRIC_SCOPE_OFF.equals(newScope)) {
                    configStore.clearBiometricAuthTimestamp();
                } else if (BiometricLockHelper.isAppProtectionEnabled(configStore)) {
                    appUnlockedInCurrentForeground = true;
                    BiometricLockHelper.recordAuthSuccess(this, configStore);
                }
                updateBiometricSettingsUi();
                VehicleWidgetProvider.refreshWidgets(this);
                LocationWidgetProvider.refreshWidgets(this);
            });
        }
        if (btnApplyBiometricGrace != null) {
            btnApplyBiometricGrace.setOnClickListener(v -> applyBiometricGracePeriod());
        }
        if (btnToggleBiometricAdvanced != null) {
            btnToggleBiometricAdvanced.setOnClickListener(v -> {
                biometricAdvancedExpanded = !biometricAdvancedExpanded;
                if (layoutBiometricAdvanced != null) {
                    layoutBiometricAdvanced.setVisibility(biometricAdvancedExpanded ? View.VISIBLE : View.GONE);
                }
                btnToggleBiometricAdvanced.setText(biometricAdvancedExpanded
                        ? R.string.btn_biometric_advanced_hide
                        : R.string.btn_biometric_advanced_show);
            });
        }
        if (switchBiometricWidgetSync != null) {
            switchBiometricWidgetSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (updatingBiometricWidgetSyncUi || configStore == null) return;
                configStore.setBiometricWidgetSyncRequired(isChecked);
                VehicleWidgetProvider.refreshWidgets(this);
                LocationWidgetProvider.refreshWidgets(this);
            });
        }
        if (btnUnlockApp != null) {
            btnUnlockApp.setOnClickListener(v -> promptAppUnlock());
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
        View.OnClickListener openSupportPage =
                v -> openExternalUrl(getString(R.string.support_url));
        if (btnAboutSupport != null) {
            btnAboutSupport.setOnClickListener(openSupportPage);
        }
        if (btnPrefSupport != null) {
            btnPrefSupport.setOnClickListener(openSupportPage);
        }
        if (btnDismissPrefSupport != null) {
            btnDismissPrefSupport.setOnClickListener(v -> {
                if (configStore != null) {
                    configStore.setSupportCardDismissed(true);
                }
                if (cardPrefSupport != null) {
                    cardPrefSupport.setVisibility(View.GONE);
                }
            });
        }
        if (btnCheckAppUpdate != null) {
            btnCheckAppUpdate.setOnClickListener(v -> {
                if (updateUiHelper != null) {
                    updateUiHelper.checkForUpdates(true);
                }
            });
        }
        View.OnClickListener openAvailableUpdate = v -> {
            if (updateUiHelper != null && availableUpdateInfo != null) {
                updateUiHelper.showUpdateDialog(availableUpdateInfo);
            }
        };
        if (bannerAppUpdate != null) {
            bannerAppUpdate.setOnClickListener(openAvailableUpdate);
        }
        if (btnUpdateBannerAction != null) {
            btnUpdateBannerAction.setOnClickListener(openAvailableUpdate);
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
                configStore.setVin(vin.toUpperCase(Locale.US));
            }
            configStore.setRegion(selectedRegion);
            configStore.setCountryCode(countryCode);
            configStore.setDefaultTempC(targetTempC);
            if (etBatteryCapacityKwh != null && etBatteryCapacityKwh.getText() != null) {
                float kwh = parseBatteryCapacityKwh(etBatteryCapacityKwh.getText().toString());
                configStore.setBatteryCapacityKwh(kwh);
                com.bydwatch.companion.data.VehicleStatusBroadcaster.notifyChanged(this);
            }
        }

        // Clear typed secret fields immediately after persisting so saved secrets stay hidden.
        updatingAccountFields = true;
        try {
            etPassword.setText("");
            etPin.setText("");
            etPassword.clearFocus();
            etPin.clearFocus();
        } finally {
            updatingAccountFields = false;
        }
        updateAccountUiState();
        return true;
    }

    private static float parseBatteryCapacityKwh(String raw) {
        if (raw == null) return 0f;
        String clean = raw.trim().replace(',', '.');
        if (clean.isEmpty()) return 0f;
        try {
            float val = Float.parseFloat(clean);
            return Float.isFinite(val) && val > 0f ? val : 0f;
        } catch (NumberFormatException ignored) {
            return 0f;
        }
    }

    private static String formatKwhInput(float kwh) {
        if (kwh <= 0f) return "";
        if (Math.abs(kwh - Math.round(kwh)) < 0.005f) {
            return String.valueOf(Math.round(kwh));
        }
        return String.format(Locale.US, "%.2f", kwh).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void attachAccountCredentialWatchers() {
        TextWatcher invalidateVinWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updatingAccountFields) return;
                invalidateFetchedVin(false);
            }
        };
        TextWatcher clearVinOnIdentityEdit = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (updatingAccountFields) return;
                invalidateFetchedVin(true);
            }
        };
        if (etUsername != null) etUsername.addTextChangedListener(clearVinOnIdentityEdit);
        if (etPassword != null) etPassword.addTextChangedListener(invalidateVinWatcher);
        if (etPin != null) etPin.addTextChangedListener(invalidateVinWatcher);
        if (etCountryCode != null) etCountryCode.addTextChangedListener(clearVinOnIdentityEdit);
        if (actvRegion != null) {
            actvRegion.setOnItemClickListener((parent, view, position, id) -> {
                if (!updatingAccountFields) {
                    invalidateFetchedVin(true);
                }
            });
            actvRegion.addTextChangedListener(clearVinOnIdentityEdit);
        }
    }

    private void invalidateFetchedVin(boolean clearVinField) {
        vinVerifiedByFetch = false;
        if (clearVinField && etVin != null) {
            updatingAccountFields = true;
            try {
                etVin.setText("");
            } finally {
                updatingAccountFields = false;
            }
        }
        updateSaveCredentialsButtonState();
    }

    private void updateSaveCredentialsButtonState() {
        if (btnSaveCredentials == null) return;
        String vinText = etVin != null && etVin.getText() != null
                ? etVin.getText().toString().trim() : "";
        boolean canSave = vinVerifiedByFetch && !vinText.isEmpty();
        btnSaveCredentials.setEnabled(canSave);
        btnSaveCredentials.setAlpha(canSave ? 1f : 0.45f);
    }

    private void saveCredentials() {
        String currentVin = etVin != null && etVin.getText() != null
                ? etVin.getText().toString().trim() : "";
        if (!vinVerifiedByFetch || currentVin.isEmpty()) {
            logMessage("Save blocked: please fetch VIN first.");
            showToast(getString(R.string.toast_fetch_vin_required), true);
            return;
        }
        if (!persistValidatedInputs(true)) {
            return;
        }

        if (configStore != null && configStore.isConfigured()) {
            setAccountExpanded(false);
            showPage(R.id.nav_controls);
        }
        updateWatchStatus();
        logMessage("Account login successful.");
        showToast(getString(R.string.toast_account_login_successful), false);
        executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
    }

    private void fetchVinAndTestLogin() {
        if (!persistValidatedInputs(false)) {
            return;
        }
        if (!vinVerifiedByFetch && configStore != null) {
            // Do not match or preserve a stale VIN before this account/region is verified.
            configStore.setVin(null);
        }

        btnFetchVin.setEnabled(false);
        if (btnSaveCredentials != null) {
            btnSaveCredentials.setEnabled(false);
            btnSaveCredentials.setAlpha(0.45f);
        }
        logMessage("Logging in and fetching vehicles...");

        repository.fetchVehicles(new BydRepository.VehiclesCallback() {
            @Override
            public void onSuccess(List<VehicleSummary> vehicles, VehicleSummary active) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    String activeVin = active != null && active.vin != null ? active.vin.trim().toUpperCase(Locale.US) : "";
                    updatingAccountFields = true;
                    try {
                        etVin.setText(activeVin);
                    } finally {
                        updatingAccountFields = false;
                    }
                    vinVerifiedByFetch = !activeVin.isEmpty();
                    updateSaveCredentialsButtonState();
                    updateAccountUiState();
                    updateHeaderVehicleInfo(null);
                    int count = vehicles != null ? vehicles.size() : (activeVin.isEmpty() ? 0 : 1);
                    logMessage("VIN fetched: " + maskVin(activeVin)
                            + (count > 1 ? " (" + count + " vehicles)" : "")
                            + ". Tap Save to continue.");
                    updateWatchStatus();
                    showToast(getString(R.string.toast_vin_fetched_tap_save), false);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    btnFetchVin.setEnabled(true);
                    vinVerifiedByFetch = false;
                    updatingAccountFields = true;
                    try {
                        if (etVin != null) etVin.setText("");
                    } finally {
                        updatingAccountFields = false;
                    }
                    if (configStore != null) {
                        configStore.setVin(null);
                    }
                    updateSaveCredentialsButtonState();
                    updateAccountUiState();
                    updateWatchStatus();
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
        btnPopupAcTimer10 = popupView.findViewById(R.id.btnAcTimer10);
        btnPopupAcTimer15 = popupView.findViewById(R.id.btnAcTimer15);
        btnPopupAcTimer20 = popupView.findViewById(R.id.btnAcTimer20);
        btnPopupAcTimer25 = popupView.findViewById(R.id.btnAcTimer25);
        btnPopupAcTimer30 = popupView.findViewById(R.id.btnAcTimer30);
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
        if (btnPopupAcTimer10 != null) {
            btnPopupAcTimer10.setOnClickListener(v -> selectAcDuration(10));
        }
        if (btnPopupAcTimer15 != null) {
            btnPopupAcTimer15.setOnClickListener(v -> selectAcDuration(15));
        }
        if (btnPopupAcTimer20 != null) {
            btnPopupAcTimer20.setOnClickListener(v -> selectAcDuration(20));
        }
        if (btnPopupAcTimer25 != null) {
            btnPopupAcTimer25.setOnClickListener(v -> selectAcDuration(25));
        }
        if (btnPopupAcTimer30 != null) {
            btnPopupAcTimer30.setOnClickListener(v -> selectAcDuration(30));
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
        btnPopupAcTimer10 = null;
        btnPopupAcTimer15 = null;
        btnPopupAcTimer20 = null;
        btnPopupAcTimer25 = null;
        btnPopupAcTimer30 = null;
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

    private void selectAcDuration(int durationMinutes) {
        int normalized = ConfigStore.normalizeAcDurationMinutes(durationMinutes);
        if (normalized != targetAcDurationMin) {
            targetAcDurationMin = normalized;
            if (configStore != null) {
                configStore.setDefaultAcDurationMinutes(targetAcDurationMin);
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

    private boolean isSingleFlightEnabled() {
        return configStore == null || configStore.isSingleFlightActionEnabled();
    }

    private void executeWatchAction(String action, int tempC, String vinOverride, int level,
                                    int tempGear, int fanLevel, int windMode) {
        if (isSingleFlightEnabled() && actionInFlight) {
            return;
        }
        if (configStore != null && !configStore.isConfigured()) {
            stopSwipeRefreshing();
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
                json.put("acDurationMin", targetAcDurationMin);
            }
            if (vinOverride != null && !vinOverride.trim().isEmpty()) {
                json.put("vin", vinOverride.trim());
            }
            if (WatchProtocol.ACTION_SEAT_HEAT.equals(action) && level >= 0) {
                json.put("level", level);
            }
        } catch (JSONException e) {
            stopSwipeRefreshing();
            logMessage("Failed to build command payload for " + action + ": " + e.getMessage());
            showToast("Failed to build " + formatActionLabel(action) + " command", false);
            return;
        }

        if (isSingleFlightEnabled()) {
            setVehicleActionInFlight(true);
        }
        logMessage("Sending " + action + "...");
        if (com.bydwatch.companion.BuildConfig.DEMO_BUILD && WatchProtocol.ACTION_SWITCH_CAR.equals(action)) {
            startDemoCarSwitch(vinOverride);
            return;
        }
        if (WatchProtocol.ACTION_SWITCH_CAR.equals(action)) {
            prepareSwitchTo(vinOverride);
        }
        if (com.bydwatch.companion.BuildConfig.DEMO_BUILD && !WatchProtocol.ACTION_SWITCH_CAR.equals(action)) {
            // Demo flavor: no cloud account exists. Keep the synthetic sample on every action so
            // a tapped button never replaces it with a sign-in error, and restamp it so the
            // "last updated" clock moves like it would after a real round trip.
            if (repository != null) {
                VehicleStatus sample = repository.currentStatusOrDemo();
                if (sample != null && WatchProtocol.ACTION_STATUS.equals(action)) {
                    // A refresh behaves like a real cloud fetch: new battery and charge state.
                    VehicleStatus fresh = randomizeDemoStatus(sample, sample.vin,
                            sample.modelName, new java.util.Random());
                    if (fresh != null) {
                        repository.putDemoSample(sample.vin, fresh);
                        sample = fresh;
                    }
                } else if (sample != null) {
                    sample = sample.toBuilder().ts(System.currentTimeMillis() / 1000L).build();
                }
                repository.setCachedStatus(sample);
            }
            setVehicleActionInFlight(false);
            stopSwipeRefreshing();
            logMessage("Demo build: " + action + " simulated, no command sent");
            return;
        }
        try {
            repository.handleWatchJson(json.toString(), responseJson -> {
                runOnUiThread(() -> handleWatchResponse(responseJson));
            });
        } catch (RuntimeException e) {
            setVehicleActionInFlight(false);
            stopSwipeRefreshing();
            logMessage("Failed to dispatch " + action + ": " + e.getMessage());
            showToast("Failed to send " + formatActionLabel(action) + " command", false);
        }
    }

    /** Demo: show the cached car immediately, then deliver "fresh" values after five seconds. */
    private void startDemoCarSwitch(String vin) {
        if (vin == null || vin.trim().isEmpty()) {
            setVehicleActionInFlight(false);
            return;
        }
        String target = vin.trim();
        List<VehicleSummary> cars = garageVehicles(lastStatus);
        VehicleSummary car = null;
        if (cars != null) {
            for (VehicleSummary v : cars) {
                if (v != null && target.equals(v.vin)) { car = v; break; }
            }
        }
        String name = car != null ? garageDisplayName(car) : target;
        String model = car != null ? VehicleSummary.formatDisplayModel(car.watchShortLabel()) : name;

        switchPreviousVin = configStore != null ? configStore.getVin() : "";
        switchTargetVin = target;
        switchingToName = name;
        if (configStore != null) {
            configStore.setVin(target);
            if (car != null) configStore.setVehicleName(car.modelName);
            configStore.setVehicleSwipeHintDismissed(true);
        }
        updateVehicleDotsVisibility();

        VehicleStatus cached = repository != null ? repository.demoSampleForVin(target) : null;
        if (cached == null && repository != null) cached = repository.currentStatusOrDemo();
        if (cached != null && repository != null) {
            repository.setCachedStatus(cached.toBuilder()
                    .vin(target)
                    .modelName(model)
                    .ts(System.currentTimeMillis() / 1000L)
                    .build());
        } else {
            updateHeaderVehicleInfo(lastStatus);
        }
        updateVehicleDotsVisibility();
        stopSwipeRefreshing();

        mainHandler.postDelayed(() -> {
            VehicleStatus base = repository != null ? repository.demoSampleForVin(target) : null;
            if (base == null && repository != null) base = repository.currentStatusOrDemo();
            VehicleStatus fresh = randomizeDemoStatus(base, target, model, new java.util.Random());
            // Clear the switching label before publishing so the fresh timestamp renders.
            switchingToName = null;
            switchPreviousVin = null;
            switchTargetVin = null;
            if (fresh != null && repository != null) {
                repository.putDemoSample(target, fresh);
                repository.setCachedStatus(fresh);
            } else if (tvLastUpdated != null) {
                tvLastUpdated.setText(R.string.state_now);
            }
            setVehicleActionInFlight(false);
            logMessage("Demo build: switched to " + name + " (simulated 5s fetch)");
            showToast("Switched to " + name, false);
        }, 5000L);
    }

    /** Real app: show this car's cached telemetry while the switched fetch is in flight. */
    private void prepareSwitchTo(String vin) {
        if (vin == null || vin.trim().isEmpty()) return;
        String target = vin.trim();
        List<VehicleSummary> cars = garageVehicles(lastStatus);
        VehicleSummary car = null;
        if (cars != null) {
            for (VehicleSummary v : cars) {
                if (v != null && target.equals(v.vin)) { car = v; break; }
            }
        }
        switchPreviousVin = configStore != null ? configStore.getVin() : "";
        switchTargetVin = target;
        switchingToName = car != null ? garageDisplayName(car) : target;

        VehicleStatus cached = repository != null ? repository.getStatusForVin(target) : null;
        if (cached != null) {
            if (configStore != null) configStore.setVin(target);
            repository.setCachedStatus(cached);
        } else if (tvLastUpdated != null) {
            tvLastUpdated.setText(getString(R.string.switching_to_fmt, switchingToName));
        }
    }

    private void setVehicleActionInFlight(boolean inFlight) {
        actionInFlight = inFlight;
        boolean enabled = !inFlight;
        // Prevent View.setEnabled(true) -> focusableViewAvailable from jumping focus and
        // scrolling NestedScrollView when action buttons re-enable after a command completes.
        int prevFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS;
        if (rootScroll != null) {
            prevFocusability = rootScroll.getDescendantFocusability();
            rootScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        try {
            View[] controls = new View[]{
                    btnRefresh,
                    btnLock,
                    btnUnlock,
                    btnAcToggle,
                    btnAcAdjust,
                    btnPopupApply,
                    btnLights,
                    btnHorn,
                    btnVentWindows,
                    btnCloseWindows,
                    btnRefreshLocation,
                    cardLocation
            };
            for (View control : controls) {
                setVehicleControlEnabled(control, enabled);
            }
            setVehicleControlEnabled(btnBatHeat, enabled && (lastStatus == null || lastStatus.canBatteryHeat));
            setVehicleControlEnabled(btnSeatHeat, enabled && (lastStatus == null || lastStatus.canSeatClimate));
            setContainerControlsEnabled(layoutHomeCustomGrid, enabled);
            setContainerControlsEnabled(layoutCombosContainer, enabled);
            if (enabled) {
                lockedDynamicControls.clear();
            }
            if (swipeRefresh != null) {
                if (inFlight) {
                    // Keep SwipeRefreshLayout enabled during its own refresh so its spinner stays visible.
                    if (!swipeRefresh.isRefreshing()) {
                        swipeRefresh.setEnabled(false);
                    }
                } else {
                    swipeRefresh.setEnabled(currentPageId == R.id.nav_controls);
                }
            }
        } finally {
            if (rootScroll != null) {
                rootScroll.setDescendantFocusability(prevFocusability);
            }
        }
    }

    private void setVehicleControlEnabled(View view, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setClickable(enabled);
        view.setAlpha(enabled ? 1.0f : DISABLED_ACTION_ALPHA);
    }

    private void setContainerControlsEnabled(View root, boolean enabled) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!enabled) {
                if ((child instanceof Button || child.isClickable()) && child.isEnabled()) {
                    lockedDynamicControls.add(child);
                    setVehicleControlEnabled(child, false);
                }
            } else if (lockedDynamicControls.remove(child)) {
                setVehicleControlEnabled(child, true);
            }
            if (child instanceof ViewGroup) {
                setContainerControlsEnabled(child, enabled);
            }
        }
    }

    private void stopSwipeRefreshing() {
        if (swipeRefresh != null && swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
    }

    private void handleWatchResponse(String responseJson) {
        if (actionInFlight) {
            setVehicleActionInFlight(false);
        }
        try {
            WatchResponse resp = WatchResponse.fromJson(new JSONObject(responseJson));
            if (resp != null) {
                if (WatchProtocol.ACTION_STATUS.equals(resp.action)) {
                    stopSwipeRefreshing();
                }
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
                        if (WatchProtocol.ACTION_SWITCH_CAR.equals(resp.action)) {
                            switchingToName = null;
                            switchPreviousVin = null;
                            switchTargetVin = null;
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
                    String detail = repository != null ? repository.getLastErrorDetail() : "";
                    String logSuffix = (detail != null && !detail.isEmpty() && !detail.equals(resp.msg))
                            ? " (" + detail + ")"
                            : "";
                    if (WatchProtocol.ACTION_SWITCH_CAR.equals(resp.action)) {
                        if (switchPreviousVin != null && !switchPreviousVin.isEmpty() && configStore != null) {
                            configStore.setVin(switchPreviousVin);
                        }
                        switchingToName = null;
                        switchPreviousVin = null;
                        switchTargetVin = null;
                        renderVehicleCarousel(lastStatus);
                        updateVehicleDotsVisibility();
                        if (tvLastUpdated != null) {
                            if (lastStatus != null && lastStatus.ts > 0) {
                                long millis = lastStatus.ts > 1_000_000_000_000L
                                        ? lastStatus.ts : lastStatus.ts * 1000L;
                                tvLastUpdated.setText(timeFormat.format(new Date(millis)));
                            } else {
                                tvLastUpdated.setText(R.string.state_now);
                            }
                        }
                    }
                    logMessage(resp.action + " failed [" + resp.err + "]: " + resp.msg + logSuffix);
                    int detailIdx = reason.indexOf(" (");
                    String toastReason = detailIdx > 0 ? reason.substring(0, detailIdx) : reason;
                    showToast(label + " failed" + (toastReason.isEmpty() ? "" : ": " + toastReason), true);
                }
            } else {
                stopSwipeRefreshing();
                logMessage("Received null response payload.");
                showToast("Empty response from vehicle", false);
            }
        } catch (Exception e) {
            stopSwipeRefreshing();
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
        VehicleWidgetProvider.refreshWidgets(this);
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
        if (switchingToName != null) {
            tvLastUpdated.setText(getString(R.string.switching_to_fmt, switchingToName));
        } else if (status.ts > 0) {
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
            updatingAccountFields = true;
            try {
                etVin.setText(status.vin);
            } finally {
                updatingAccountFields = false;
            }
        }

        updateClimateControlsUi();
        updateHeaderVehicleInfo(status);
        updateLocationCard(status);
        updateAccountUiState();
        VehicleWidgetProvider.refreshWidgets(this);
        if (widgetComboUiHelper != null) {
            widgetComboUiHelper.refreshUi();
        }
        if (homeLayoutEditorHelper != null) {
            homeLayoutEditorHelper.renderCustomButtonsGrid();
        }
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
        updateHomeSectionsVisibility();
    }

    private void updateHomeSectionsVisibility() {
        boolean showClimate = configStore == null || configStore.isShowHomeClimate();
        boolean showAlerts = configStore == null || configStore.isShowHomeAlerts();
        boolean hideExtra = configStore != null && configStore.isHideExtraControls();
        boolean showLocation = configStore == null || configStore.isShowHomeLocation();
        boolean showHealth = configStore == null || configStore.isShowHomeHealth();

        if (cardClimateControl != null) {
            cardClimateControl.setVisibility(showClimate ? View.VISIBLE : View.GONE);
        }
        if (layoutAlertControls != null) {
            layoutAlertControls.setVisibility(showAlerts ? View.VISIBLE : View.GONE);
        }
        int extraVis = hideExtra ? View.GONE : View.VISIBLE;
        if (layoutComfortRow1 != null) layoutComfortRow1.setVisibility(extraVis);
        if (layoutComfortRow2 != null) layoutComfortRow2.setVisibility(extraVis);
        if (cardLocation != null) {
            cardLocation.setVisibility(showLocation ? View.VISIBLE : View.GONE);
        }
        if (layoutHealthPanel != null) {
            layoutHealthPanel.setVisibility(showHealth ? View.VISIBLE : View.GONE);
        }
        if (switchHideExtraControls != null && switchHideExtraControls.isChecked() != hideExtra) {
            updatingHideExtraSwitch = true;
            try {
                switchHideExtraControls.setChecked(hideExtra);
            } finally {
                updatingHideExtraSwitch = false;
            }
        }
    }

    private void openHomeLayoutEditor() {
        if (homeLayoutEditorHelper != null) {
            homeLayoutEditorHelper.showEditLayoutDialog();
        }
    }

    private void dispatchSingleHomeAction(String actionKey) {
        if (actionKey == null) return;
        switch (actionKey) {
            case WidgetSlotConfig.SINGLE_LOCK:
                executeWatchAction(WatchProtocol.ACTION_LOCK, -1);
                break;
            case WidgetSlotConfig.SINGLE_UNLOCK:
                executeWatchAction(WatchProtocol.ACTION_UNLOCK, -1);
                break;
            case WidgetSlotConfig.SINGLE_AC_TOGGLE:
                toggleAcState();
                break;
            case WidgetSlotConfig.SINGLE_LIGHTS:
                executeWatchAction(WatchProtocol.ACTION_LIGHTS, -1);
                break;
            case WidgetSlotConfig.SINGLE_HORN:
                executeWatchAction(WatchProtocol.ACTION_HORN, -1);
                break;
            case WidgetSlotConfig.SINGLE_VENT_WINDOWS:
                executeWatchAction(WatchProtocol.ACTION_VENT_WINDOWS, -1);
                break;
            case WidgetSlotConfig.SINGLE_CLOSE_WINDOWS:
                executeWatchAction(WatchProtocol.ACTION_CLOSE_WINDOWS, -1);
                break;
            case WidgetSlotConfig.SINGLE_BAT_HEAT: {
                boolean currentlyOn = lastStatus != null && Boolean.TRUE.equals(lastStatus.batteryHeatOn);
                executeWatchAction(currentlyOn
                        ? WatchProtocol.ACTION_BATTERY_HEAT_OFF
                        : WatchProtocol.ACTION_BATTERY_HEAT_ON, -1);
                break;
            }
            case WidgetSlotConfig.SINGLE_SEAT_HEAT: {
                int currentLevel = lastStatus != null ? lastStatus.driverSeatLevel : 0;
                int nextLevel = (currentLevel + 1) % 3;
                executeWatchAction(WatchProtocol.ACTION_SEAT_HEAT, -1, null, nextLevel);
                break;
            }
            case WidgetSlotConfig.SINGLE_LOCATION:
                executeWatchAction(WatchProtocol.ACTION_LOCATION, -1);
                break;
            case WidgetSlotConfig.SINGLE_STATUS:
            default:
                executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
                break;
        }
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
        String timerSuffix = " · " + getString(R.string.ac_timer_min_short_fmt, targetAcDurationMin);
        if (manual) {
            return getString(R.string.climate_preset_manual_fmt,
                    targetTempGear, targetFanLevel, formatWindModeLabel(targetWindMode))
                    + timerSuffix;
        }
        String tempUnit = configStore != null ? configStore.getTempUnit() : UnitPreferences.TEMP_C;
        return UnitPreferences.formatTemp(targetTempC, tempUnit) + timerSuffix;
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
        styleVentChip(btnPopupAcTimer10, targetAcDurationMin == 10);
        styleVentChip(btnPopupAcTimer15, targetAcDurationMin == 15);
        styleVentChip(btnPopupAcTimer20, targetAcDurationMin == 20);
        styleVentChip(btnPopupAcTimer25, targetAcDurationMin == 25);
        styleVentChip(btnPopupAcTimer30, targetAcDurationMin == 30);
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
        else {
            VehicleWidgetProvider.refreshWidgets(this);
            if (widgetComboUiHelper != null) widgetComboUiHelper.refreshUi();
        }
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
        if (widgetComboUiHelper != null) {
            widgetComboUiHelper.refreshUi();
        }
    }

    private void runComboFromUi(CustomActionCombo combo) {
        if (combo == null || (isSingleFlightEnabled() && actionInFlight)) return;
        if (configStore != null && !configStore.isConfigured()) {
            logMessage("Combo " + combo.name + " failed: BYD credentials are not configured.");
            showToast("BYD credentials are not configured", false);
            return;
        }
        if (isSingleFlightEnabled()) {
            setVehicleActionInFlight(true);
        }
        logMessage("Running combo: " + combo.name);
        try {
            VehicleWidgetProvider.executeCombo(
                    this,
                    combo,
                    true,
                    new VehicleWidgetProvider.ComboProgressListener() {
                        @Override
                        public void onStepProgress(int stepIndex, int totalSteps, String label) {
                            showToast(getString(R.string.combo_running_fmt, combo.name, label), false);
                        }

                        @Override
                        public void onFinished(boolean ok, String message) {
                            setVehicleActionInFlight(false);
                            logMessage("Combo " + combo.name + ": " + message);
                            showToast(message, true);
                            if (repository != null && repository.getCachedStatus() != null) {
                                updateTelemetryUi(repository.getCachedStatus());
                            }
                        }
                    },
                    null
            );
        } catch (RuntimeException e) {
            setVehicleActionInFlight(false);
            logMessage("Combo " + combo.name + " failed: " + e.getMessage());
            showToast("Combo failed: " + e.getMessage(), true);
        }
    }

    private String biometricScopeFromButtonId(int checkedId) {
        if (checkedId == R.id.btnBioScopeApp) return ConfigStore.BIOMETRIC_SCOPE_APP;
        if (checkedId == R.id.btnBioScopeWidget) return ConfigStore.BIOMETRIC_SCOPE_WIDGET;
        if (checkedId == R.id.btnBioScopeBoth) return ConfigStore.BIOMETRIC_SCOPE_BOTH;
        return ConfigStore.BIOMETRIC_SCOPE_OFF;
    }

    private void updateBiometricSettingsUi() {
        String scope = configStore != null ? configStore.getBiometricScope() : ConfigStore.DEFAULT_BIOMETRIC_SCOPE;
        if (toggleBiometricScope != null) {
            updatingBiometricScopeUi = true;
            try {
                int targetId = R.id.btnBioScopeOff;
                if (ConfigStore.BIOMETRIC_SCOPE_APP.equals(scope)) {
                    targetId = R.id.btnBioScopeApp;
                } else if (ConfigStore.BIOMETRIC_SCOPE_WIDGET.equals(scope)) {
                    targetId = R.id.btnBioScopeWidget;
                } else if (ConfigStore.BIOMETRIC_SCOPE_BOTH.equals(scope)) {
                    targetId = R.id.btnBioScopeBoth;
                }
                if (toggleBiometricScope.getCheckedButtonId() != targetId) {
                    toggleBiometricScope.check(targetId);
                }
            } finally {
                updatingBiometricScopeUi = false;
            }
        }
        boolean active = !ConfigStore.BIOMETRIC_SCOPE_OFF.equals(scope);
        if (layoutBiometricOptions != null) {
            layoutBiometricOptions.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (etBiometricGraceMinutes != null && configStore != null) {
            etBiometricGraceMinutes.setText(String.valueOf(configStore.getBiometricGraceMinutes()));
        }
        if (switchBiometricWidgetSync != null) {
            updatingBiometricWidgetSyncUi = true;
            try {
                switchBiometricWidgetSync.setChecked(configStore != null && configStore.isBiometricWidgetSyncRequired());
                switchBiometricWidgetSync.setEnabled(BiometricLockHelper.isWidgetProtectionEnabled(configStore));
            } finally {
                updatingBiometricWidgetSyncUi = false;
            }
        }
    }

    private void applyBiometricGracePeriod() {
        if (configStore == null || etBiometricGraceMinutes == null) return;
        String raw = etBiometricGraceMinutes.getText() != null
                ? etBiometricGraceMinutes.getText().toString().trim()
                : "";
        int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            showToast(getString(R.string.biometric_grace_invalid), false);
            return;
        }
        if (parsed < 0 || parsed > ConfigStore.MAX_BIOMETRIC_GRACE_MINUTES) {
            showToast(getString(R.string.biometric_grace_invalid), false);
            return;
        }
        configStore.setBiometricGraceMinutes(parsed);
        etBiometricGraceMinutes.setText(String.valueOf(parsed));
        etBiometricGraceMinutes.clearFocus();
        if (parsed == 0) {
            showToast(getString(R.string.biometric_grace_always), false);
        } else {
            showToast(getString(R.string.biometric_grace_saved, parsed), false);
        }
    }

    private void enforceAppLockIfNeeded(boolean triggerPrompt) {
        if (BiometricLockHelper.isAuthRequiredForApp(this, configStore, appUnlockedInCurrentForeground)) {
            setAppLockOverlayVisible(true);
            if (triggerPrompt && !biometricPromptShowing) {
                promptAppUnlock();
            }
        } else {
            setAppLockOverlayVisible(false);
        }
    }

    private void setAppLockOverlayVisible(boolean visible) {
        if (layoutAppLockOverlay != null) {
            layoutAppLockOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void promptAppUnlock() {
        if (biometricPromptShowing || isFinishing() || isDestroyed()) {
            return;
        }
        biometricPromptShowing = true;
        BiometricLockHelper.authenticate(
                this,
                getString(R.string.biometric_app_prompt_title),
                getString(R.string.biometric_app_prompt_subtitle),
                new BiometricLockHelper.AuthCallback() {
                    @Override
                    public void onSucceeded() {
                        biometricPromptShowing = false;
                        appUnlockedInCurrentForeground = true;
                        BiometricLockHelper.recordAuthSuccess(MainActivity.this, configStore);
                        setAppLockOverlayVisible(false);
                    }

                    @Override
                    public void onFailedOrCancelled(@NonNull String message) {
                        biometricPromptShowing = false;
                        setAppLockOverlayVisible(true);
                    }
                }
        );
    }

    /** Rename dialog for one garage car; the override is stored per VIN. */
    private void showRenameVehicleDialog(VehicleSummary car) {
        if (configStore == null || car == null || car.vin == null) return;

        editVehicleVin = car.vin;
        editVehicleEmoji = configStore.getVehicleIconEmoji(car.vin);
        editVehiclePhoto = configStore.getVehicleIconPhoto(car.vin);
        editVehicleUsePhoto = editVehiclePhoto != null && !editVehiclePhoto.isEmpty();

        View content = LayoutInflater.from(this).inflate(R.layout.sheet_edit_vehicle, null);
        editVehicleSheet = content;
        TextView title = content.findViewById(R.id.tvEditVehicleTitle);
        EditText input = content.findViewById(R.id.etGarageRename);
        String saved = configStore.getVehicleCustomName(car.vin);
        input.setText(saved);
        input.setHint(VehicleSummary.formatDisplayModel(car.watchShortLabel()));
        input.setSelection(input.getText() != null ? input.getText().length() : 0);
        if (title != null) {
            String display = saved != null && !saved.trim().isEmpty() ? saved.trim() : car.watchShortLabel();
            title.setText(getString(R.string.edit_vehicle_title_fmt, display));
        }

        GridLayout grid = content.findViewById(R.id.gridEditEmoji);
        buildEmojiGrid(grid);
        bindEditVehiclePreview();
        updateEditIconModeUi();

        MaterialButtonToggleGroup toggle = content.findViewById(R.id.toggleEditIconMode);
        if (toggle != null) {
            toggle.check(editVehicleUsePhoto ? R.id.btnEditIconPhoto : R.id.btnEditIconEmoji);
            toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                editVehicleUsePhoto = checkedId == R.id.btnEditIconPhoto;
                updateEditIconModeUi();
            });
        }

        com.google.android.material.button.MaterialButton pick =
                content.findViewById(R.id.btnEditPickPhoto);
        if (pick != null) {
            pick.setOnClickListener(v -> {
                if (vehiclePhotoPicker != null) vehiclePhotoPicker.launch(new String[]{"image/*"});
            });
        }

        com.google.android.material.button.MaterialButton reset =
                content.findViewById(R.id.btnResetVehicle);
        if (reset != null) {
            reset.setOnClickListener(v -> {
                if (configStore == null || editVehicleVin == null) return;
                configStore.setVehicleIconEmoji(editVehicleVin, "");
                configStore.setVehicleIconPhoto(editVehicleVin, "");
                VehicleIconStore.deletePhoto(this, editVehicleVin);
                vehicleIconCache.evictAll();
                dismissEditVehicleSheet();
                applyVehicleRename(editVehicleVin, "");
                showToast(getString(R.string.garage_rename_cleared), false);
            });
        }

        com.google.android.material.button.MaterialButton cancel =
                content.findViewById(R.id.btnCancelVehicle);
        if (cancel != null) cancel.setOnClickListener(v -> dismissEditVehicleSheet());

        com.google.android.material.button.MaterialButton save =
                content.findViewById(R.id.btnSaveVehicle);
        if (save != null) {
            save.setOnClickListener(v -> {
                if (configStore == null || editVehicleVin == null) return;
                String name = input.getText() != null
                        ? input.getText().toString().trim().replaceAll("\\s+", " ")
                        : "";
                if (editVehicleUsePhoto && editVehiclePhoto != null && !editVehiclePhoto.isEmpty()) {
                    configStore.setVehicleIconPhoto(editVehicleVin, editVehiclePhoto);
                    configStore.setVehicleIconEmoji(editVehicleVin, "");
                } else {
                    configStore.setVehicleIconPhoto(editVehicleVin, "");
                    VehicleIconStore.deletePhoto(this, editVehicleVin);
                    String emoji = editVehicleEmoji != null && !editVehicleEmoji.isEmpty()
                            ? editVehicleEmoji
                            : VehicleIconStore.DEFAULT_EMOJI;
                    configStore.setVehicleIconEmoji(editVehicleVin, emoji);
                }
                vehicleIconCache.evictAll();
                dismissEditVehicleSheet();
                applyVehicleRename(editVehicleVin, name);
            });
        }

        editVehicleDialog = new BottomSheetDialog(this);
        editVehicleDialog.setContentView(content);
        editVehicleDialog.setOnDismissListener(d -> {
            editVehicleDialog = null;
            editVehicleSheet = null;
            editVehicleVin = null;
        });
        editVehicleDialog.show();
    }

    private void dismissEditVehicleSheet() {
        if (editVehicleDialog != null) {
            editVehicleDialog.dismiss();
        }
    }

    private void buildEmojiGrid(GridLayout grid) {
        if (grid == null) return;
        grid.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(42 * density);
        String selected = VehicleIconStore.emojiOrDefault(editVehicleEmoji);
        for (String emoji : VehicleIconStore.EMOJIS) {
            TextView cell = new TextView(this);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = size;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(Math.round(2 * density), Math.round(2 * density),
                    Math.round(2 * density), Math.round(2 * density));
            cell.setLayoutParams(lp);
            cell.setGravity(Gravity.CENTER);
            cell.setText(emoji);
            cell.setTextSize(20);
            cell.setBackgroundResource(R.drawable.bg_panel_inner);
            boolean on = emoji.equals(selected) && !editVehicleUsePhoto;
            cell.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                    on ? R.color.accent_emerald_dark : R.color.surface_inner)));
            cell.setOnClickListener(v -> {
                editVehicleEmoji = emoji;
                editVehicleUsePhoto = false;
                if (editVehicleSheet != null) {
                    MaterialButtonToggleGroup group = editVehicleSheet.findViewById(R.id.toggleEditIconMode);
                    if (group != null) group.check(R.id.btnEditIconEmoji);
                }
                buildEmojiGrid(grid);
                bindEditVehiclePreview();
                updateEditIconModeUi();
            });
            grid.addView(cell);
        }
    }

    private void updateEditIconModeUi() {
        if (editVehicleSheet == null) return;
        View grid = editVehicleSheet.findViewById(R.id.gridEditEmoji);
        View photo = editVehicleSheet.findViewById(R.id.layoutEditPhoto);
        if (grid != null) grid.setVisibility(editVehicleUsePhoto ? View.GONE : View.VISIBLE);
        if (photo != null) photo.setVisibility(editVehicleUsePhoto ? View.VISIBLE : View.GONE);
    }

    private void bindEditVehiclePreview() {
        if (editVehicleSheet == null) return;
        ImageView preview = editVehicleSheet.findViewById(R.id.ivEditIconPreview);
        TextView emojiView = editVehicleSheet.findViewById(R.id.tvEditIconEmoji);
        if (preview == null || emojiView == null) return;
        Bitmap bitmap = null;
        if (editVehicleUsePhoto && editVehiclePhoto != null && !editVehiclePhoto.isEmpty()) {
            bitmap = vehicleIconCache.get(editVehiclePhoto);
            if (bitmap == null) {
                bitmap = VehicleIconStore.loadPhoto(editVehiclePhoto);
                if (bitmap != null) vehicleIconCache.put(editVehiclePhoto, bitmap);
            }
        }
        if (bitmap != null) {
            preview.setImageBitmap(bitmap);
            preview.setVisibility(View.VISIBLE);
            emojiView.setVisibility(View.GONE);
        } else {
            preview.setVisibility(View.GONE);
            emojiView.setText(VehicleIconStore.emojiOrDefault(editVehicleEmoji));
            emojiView.setVisibility(View.VISIBLE);
        }
    }

    private void applyVehicleRename(String vin, String name) {
        if (configStore == null || vin == null) return;
        configStore.setVehicleCustomName(vin, name);
        updateHeaderVehicleInfo(lastStatus);
        renderGarageCard();
        if (widgetComboUiHelper != null) {
            widgetComboUiHelper.refreshUi();
        }
        VehicleWidgetProvider.refreshWidgets(this);
        LocationWidgetProvider.refreshWidgets(this);
        String msg = name.isEmpty()
                ? getString(R.string.garage_rename_cleared)
                : getString(R.string.garage_rename_done_fmt, name);
        logMessage(msg);
        showToast(msg, false);
    }

    /** Old installs stored one global override; adopt it for the active car, then clear it. */
    private void migrateLegacyVehicleName() {
        if (configStore == null) return;
        String legacy = configStore.getCustomVehicleName();
        if (legacy == null || legacy.trim().isEmpty()) return;
        String vin = configStore.getVin();
        if (vin != null && !vin.trim().isEmpty() && configStore.getVehicleCustomName(vin).trim().isEmpty()) {
            configStore.setVehicleCustomName(vin, legacy.trim());
        }
        configStore.setCustomVehicleName("");
    }

    private void updateHeaderVehicleInfo(VehicleStatus status) {
        String activeVin = status != null && status.vin != null && !status.vin.trim().isEmpty()
                ? status.vin.trim()
                : (configStore != null && configStore.getVin() != null ? configStore.getVin().trim() : "");
        String customModel = configStore != null ? configStore.resolveVehicleCustomName(activeVin) : "";
        String displayModel = (customModel != null && !customModel.trim().isEmpty())
                ? customModel.trim()
                : VehicleSummary.formatDisplayModel(resolveDisplayModelName(status));

        if (tvHeaderTitle != null) {
            tvHeaderTitle.setText(R.string.app_name);
        }
        updateHeaderSubtitleForPage(currentPageId);
        if (tvDrawerCarModel != null) {
            tvDrawerCarModel.setText(displayModel);
        }
        if (tvControlsTitle != null) {
            tvControlsTitle.setText(R.string.controls_section_title);
        }
        renderVehicleCarousel(status);
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

    /** The garage as the account knows it: live status first, else the persisted vehicle list. */
    private List<VehicleSummary> garageVehicles(VehicleStatus status) {
        if (status != null && status.vehicles != null && !status.vehicles.isEmpty()) {
            return status.vehicles;
        }
        return configStore != null ? configStore.getVehicles() : null;
    }

    /** Chevron + tap target on the header car name, only when there is another car to switch to. */
    private String vehicleCarouselKey(List<VehicleSummary> cars) {
        StringBuilder sb = new StringBuilder(currentActiveVin());
        if (cars != null) {
            for (VehicleSummary car : cars) {
                if (car == null) continue;
                sb.append('|').append(car.vin)
                        .append(',').append(garageDisplayName(car))
                        .append(',').append(configStore != null ? configStore.getVehicleIconEmoji(car.vin) : "")
                        .append(',').append(configStore != null ? configStore.getVehicleIconPhoto(car.vin) : "");
            }
        }
        return sb.toString();
    }

    private void renderVehicleCarousel(VehicleStatus status) {
        if (scrollVehicle == null || layoutVehicleCards == null) return;
        List<VehicleSummary> cars = garageVehicles(status);
        boolean hasCars = cars != null && !cars.isEmpty();
        boolean multi = cars != null && cars.size() > 1;
        vehicleCarouselHasCars = hasCars;

        // Cards only show identity (name, icon, VIN), so telemetry refreshes must not rebuild
        // them or reset the carousel scroll.
        String key = vehicleCarouselKey(cars);
        boolean unchanged = key.equals(vehicleCarouselKey);
        vehicleCarouselKey = key;

        float density = getResources().getDisplayMetrics().density;
        int available = getResources().getDisplayMetrics().widthPixels - Math.round(32 * density);
        int peek = Math.round(26 * density);
        vehicleCardWidthPx = Math.max(Math.round(200 * density), available - peek * 2);

        if (unchanged && hasCars) {
            updateVehicleDotsVisibility();
            if (layoutVehicleCarousel != null) {
                layoutVehicleCarousel.setVisibility(
                        currentPageId == R.id.nav_controls ? View.VISIBLE : View.GONE);
            }
            return;
        }

        layoutVehicleCards.removeAllViews();
        if (cars != null) {
            for (VehicleSummary car : cars) {
                if (car == null) continue;
                View card = buildVehicleCard(car, multi);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        multi ? vehicleCardWidthPx : android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(multi ? Math.round(8 * density) : 0);
                card.setLayoutParams(lp);
                layoutVehicleCards.addView(card);
            }
        }

        scrollVehicle.setEnabled(multi);
        scrollVehicle.setHorizontalScrollBarEnabled(false);
        if (scrollVehicle.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) scrollVehicle.getParent()).setClipChildren(false);
        }

        int index = Math.max(0, indexOfVehicle(cars, currentActiveVin()));
        rebuildVehicleDots(cars != null ? cars.size() : 0, index);
        updateVehicleDotsVisibility();
        final int target = index;
        final int step = multi ? vehicleCardWidthPx + Math.round(8 * density) : 0;
        scrollVehicle.scrollTo(target * step, 0);
        scrollVehicle.post(() -> scrollVehicle.post(() -> {
            if (scrollVehicle != null) scrollVehicle.scrollTo(target * step, 0);
        }));

        if (layoutVehicleCarousel != null) {
            layoutVehicleCarousel.setVisibility(
                    hasCars && currentPageId == R.id.nav_controls ? View.VISIBLE : View.GONE);
        }
    }

    private View buildVehicleCard(VehicleSummary car, boolean multi) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_vehicle_card, layoutVehicleCards, false);
        ImageView icon = card.findViewById(R.id.ivCarouselIcon);
        TextView iconEmoji = card.findViewById(R.id.tvCarouselIconEmoji);
        TextView name = card.findViewById(R.id.tvCarouselName);
        TextView meta = card.findViewById(R.id.tvCarouselMeta);
        Button switchButton = card.findViewById(R.id.btnCarouselSwitch);

        name.setText(garageDisplayName(car));
        String region = configStore != null
                ? RegionOption.findByCode(configStore.getRegion()).formatLabel()
                : "";
        String model = VehicleSummary.formatDisplayModel(car.watchShortLabel());
        boolean hasCustomName = configStore != null
                && !configStore.getVehicleCustomName(car.vin).trim().isEmpty();
        meta.setText(hasCustomName
                ? getString(R.string.vehicle_card_meta_fmt, model, region, maskVin(car.vin))
                : getString(R.string.vehicle_card_meta_short_fmt, region, maskVin(car.vin)));
        bindVehicleIcon(icon, iconEmoji, car.vin);

        switchButton.setVisibility(multi ? View.VISIBLE : View.GONE);
        switchButton.setOnClickListener(v -> showGarageSheet());

        setupVehicleSwipeHandler(multi);
        return card;
    }

    private void setupVehicleSwipeHandler(boolean multi) {
        if (scrollVehicle == null) return;
        if (!multi) {
            scrollVehicle.setOnTouchListener(null);
            scrollVehicle.setOnScrollChangeListener(null);
            return;
        }
        scrollVehicle.setOnTouchListener((v, event) -> {
            float density = getResources().getDisplayMetrics().density;
            int width = vehicleCardWidthPx + Math.round(8 * density);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    vehicleDownX = event.getX();
                    vehicleDownY = event.getY();
                    // Base the direction on the active car, not on scrollX: the carousel may
                    // still be settling from a programmatic scroll.
                    vehicleDragStartIndex = indexOfVehicle(garageVehicles(lastStatus), currentActiveVin());
                    if (vehicleVelocityTracker == null) {
                        vehicleVelocityTracker = android.view.VelocityTracker.obtain();
                    } else {
                        vehicleVelocityTracker.clear();
                    }
                    vehicleVelocityTracker.addMovement(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (vehicleVelocityTracker != null) vehicleVelocityTracker.addMovement(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float dx = event.getX() - vehicleDownX;
                    float dy = event.getY() - vehicleDownY;
                    float vx = 0f;
                    if (vehicleVelocityTracker != null) {
                        vehicleVelocityTracker.computeCurrentVelocity(1000);
                        vx = vehicleVelocityTracker.getXVelocity();
                    }
                    if (vehicleVelocityTracker != null) {
                        vehicleVelocityTracker.recycle();
                        vehicleVelocityTracker = null;
                    }
                    final float fdx = dx;
                    final float fdy = dy;
                    final float fvx = vx;
                    // Let the ScrollView finish its own gesture, then snap after its fling starts
                    // (smoothScrollTo replaces the fling animation).
                    v.postDelayed(() -> finishVehicleSwipe(fdx, fdy, fvx), 60L);
                default:
                    break;
            }
            return false;
        });

    }

    /**
     * Forgiving swipe: a modest drag or a quick fling advances one card, instead of requiring
     * the card to travel half-way. Wraps around at the ends.
     */
    private void finishVehicleSwipe(float dx, float dy, float velocityX) {
        if (layoutVehicleCards == null) return;
        long now = System.currentTimeMillis();
        if (now - lastVehicleSwipeAt < 350L) return;
        lastVehicleSwipeAt = now;
        int count = layoutVehicleCards.getChildCount();
        if (count < 2) return;

        float density = getResources().getDisplayMetrics().density;
        int width = vehicleCardWidthPx + Math.round(8 * density);
        if (width <= 0) return;

        boolean horizontal = Math.abs(dx) > Math.abs(dy) * 1.2f;
        if (!horizontal && Math.abs(dx) < 10 * density && Math.abs(dy) < 10 * density) {
            if (count > 1) showGarageSheet();
            return;
        }
        float distanceThreshold = 14 * density;
        float flingThreshold = 300 * density;

        int target = vehicleDragStartIndex;
        if (horizontal) {
            if (dx < -distanceThreshold || velocityX < -flingThreshold) {
                target = (vehicleDragStartIndex + 1) % count;
            } else if (dx > distanceThreshold || velocityX > flingThreshold) {
                target = (vehicleDragStartIndex - 1 + count) % count;
            }
        }

        scrollVehicle.smoothScrollTo(target * width, 0);
        rebuildVehicleDots(count, target);

        List<VehicleSummary> cars = garageVehicles(lastStatus);
        if (cars == null || target >= cars.size()) return;
        VehicleSummary car = cars.get(target);
        if (car == null || car.vin == null) return;
        if (car.vin.equals(currentActiveVin())) return;
        if (actionInFlight) {
            scrollVehicle.smoothScrollTo(Math.max(0, vehicleDragStartIndex) * width, 0);
            return;
        }
        if (configStore != null) configStore.setVehicleSwipeHintDismissed(true);
        updateVehicleDotsVisibility();
        executeWatchAction(WatchProtocol.ACTION_SWITCH_CAR, -1, car.vin, -1);
    }

    private void updateVehicleDotsVisibility() {
        boolean multi = vehicleCarouselHasCars && layoutVehicleCards != null
                && layoutVehicleCards.getChildCount() > 1;
        if (layoutVehicleDots != null) {
            layoutVehicleDots.setVisibility(multi ? View.VISIBLE : View.GONE);
        }
        if (tvVehicleSwipeHint != null) {
            boolean dismissed = configStore != null && configStore.isVehicleSwipeHintDismissed();
            tvVehicleSwipeHint.setVisibility(multi && !dismissed ? View.VISIBLE : View.GONE);
        }
    }

    private void rebuildVehicleDots(int count, int selected) {
        if (layoutVehicleDots == null) return;
        layoutVehicleDots.removeAllViews();
        if (count <= 1) {
            layoutVehicleDots.setVisibility(View.GONE);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(5 * density);
        int gap = Math.round(5 * density);
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    i == selected ? size * 2 : size, size);
            lp.setMarginEnd(gap);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_console);
            dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                    i == selected ? R.color.accent_emerald : R.color.text_muted)));
            layoutVehicleDots.addView(dot);
        }
        layoutVehicleDots.setVisibility(View.VISIBLE);
    }

    private static int indexOfVehicle(List<VehicleSummary> cars, String vin) {
        if (cars == null || vin == null) return 0;
        for (int i = 0; i < cars.size(); i++) {
            VehicleSummary car = cars.get(i);
            if (car != null && vin.equals(car.vin)) return i;
        }
        return 0;
    }

    private void bindVehicleIcon(ImageView photo, TextView emoji, String vin) {
        String path = configStore != null ? configStore.getVehicleIconPhoto(vin) : "";
        Bitmap bitmap = null;
        if (path != null && !path.isEmpty()) {
            bitmap = vehicleIconCache.get(path);
            if (bitmap == null) {
                bitmap = VehicleIconStore.loadPhoto(path);
                if (bitmap != null) vehicleIconCache.put(path, bitmap);
            }
        }
        if (bitmap != null) {
            photo.setImageBitmap(bitmap);
            photo.setVisibility(View.VISIBLE);
            emoji.setVisibility(View.GONE);
        } else {
            photo.setVisibility(View.GONE);
            String stored = configStore != null ? configStore.getVehicleIconEmoji(vin) : "";
            emoji.setText(VehicleIconStore.emojiOrDefault(stored));
            emoji.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Account page: show what Fetch VIN returned, per car. Until vehicles are fetched the card
     * stays hidden; once present it lists every car with its telemetry (when known) and an
     * ACTIVE badge on the one driving the controls page.
     */
    private void renderGarageCard() {
        if (cardGarage == null) return;
        List<VehicleSummary> cars = garageVehicles(lastStatus);
        boolean has = cars != null && !cars.isEmpty();
        garageHasContent = has;
        cardGarage.setVisibility(has && currentPageId == R.id.nav_account ? View.VISIBLE : View.GONE);
        if (!has) return;

        String activeVin = lastStatus != null && lastStatus.vin != null && !lastStatus.vin.trim().isEmpty()
                ? lastStatus.vin.trim()
                : (configStore != null && configStore.getVin() != null ? configStore.getVin().trim() : "");

        if (tvGarageCount != null) {
            tvGarageCount.setText(getResources().getQuantityString(
                    R.plurals.garage_count, cars.size(), cars.size()));
        }
        if (tvGarageMeta != null) {
            long fetchedAt = configStore != null ? configStore.getVehiclesFetchedAt() : 0L;
            String activeName = displayNameForVin(cars, activeVin);
            if (fetchedAt > 0L) {
                tvGarageMeta.setText(getString(R.string.garage_meta_fmt,
                        garageTimeFormat.format(new Date(fetchedAt)), activeName));
            } else {
                tvGarageMeta.setText(getString(R.string.garage_meta_no_time, activeName));
            }
        }
        if (layoutGarageRows != null) {
            layoutGarageRows.removeAllViews();
            for (VehicleSummary car : cars) {
                if (car == null) continue;
                View row = buildGarageRow(layoutGarageRows, car, activeVin, true);
                row.setOnClickListener(v -> switchToGarageVehicle(car));
                layoutGarageRows.addView(row);
            }
        }
    }

    /** Row label for a car: its per-VIN rename when set, else the detected model. */
    private String garageDisplayName(VehicleSummary car) {
        if (car == null) return getString(R.string.garage_title);
        String custom = configStore != null ? configStore.resolveVehicleCustomName(car.vin) : "";
        return custom != null && !custom.trim().isEmpty() ? custom.trim() : car.watchShortLabel();
    }

    private String displayNameForVin(List<VehicleSummary> cars, String vin) {
        if (cars != null && vin != null) {
            for (VehicleSummary v : cars) {
                if (v != null && vin.equals(v.vin)) return garageDisplayName(v);
            }
        }
        return getString(R.string.garage_title);
    }

    /** Telemetry for a garage row: the active car's live status, or a demo sample in demo builds. */
    private VehicleStatus garageStatusForVin(String vin) {
        if (vin == null) return null;
        if (lastStatus != null && vin.equals(lastStatus.vin)) return lastStatus;
        VehicleStatus cached = repository != null ? repository.currentStatusOrDemo() : null;
        if (cached != null && vin.equals(cached.vin)) return cached;
        return repository != null ? repository.demoSampleForVin(vin) : null;
    }

    private View buildGarageRow(ViewGroup parent, VehicleSummary car, String activeVin, boolean allowRename) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_garage_car, parent, false);
        TextView name = row.findViewById(R.id.tvGarageRowName);
        TextView meta = row.findViewById(R.id.tvGarageRowMeta);
        android.widget.RadioButton radio = row.findViewById(R.id.rbGarageRow);
        ImageView icon = row.findViewById(R.id.ivGarageRowIcon);
        TextView iconEmoji = row.findViewById(R.id.tvGarageRowEmoji);
        boolean active = car.vin != null && car.vin.equals(activeVin);

        if (icon != null && iconEmoji != null) {
            bindVehicleIcon(icon, iconEmoji, car.vin);
        }
        name.setText(garageDisplayName(car));
        meta.setText(buildGarageMeta(car, active));
        if (radio != null) {
            radio.setChecked(active);
        }
        if (active) {
            name.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald));
        }
        View rename = row.findViewById(R.id.btnGarageRowRename);
        if (rename != null) {
            rename.setVisibility(allowRename ? View.VISIBLE : View.GONE);
            rename.setOnClickListener(v -> showRenameVehicleDialog(car));
        }
        return row;
    }

    private String buildGarageMeta(VehicleSummary car, boolean active) {
        String masked = maskVin(car.vin);
        VehicleStatus status = garageStatusForVin(car.vin);
        if (status == null) {
            return masked + " · " + getString(R.string.garage_row_tap);
        }
        String distUnit = configStore != null ? configStore.getDistanceUnit() : null;
        StringBuilder sb = new StringBuilder(masked)
                .append(" · ").append(status.soc).append("%")
                .append(" · ").append(UnitPreferences.formatDistance(status.rangeKm, distUnit));
        if (status.chargingState == VehicleStatus.CHARGING_ACTIVE) {
            sb.append(" · ").append(getString(R.string.garage_row_charging));
        }
        return sb.toString();
    }

    private void switchToGarageVehicle(VehicleSummary car) {
        if (car == null || car.vin == null || car.vin.trim().isEmpty()) return;
        String activeVin = configStore != null ? configStore.getVin() : null;
        if (car.vin.equals(activeVin)) return;
        executeWatchAction(WatchProtocol.ACTION_SWITCH_CAR, -1, car.vin, -1);
    }

    /** Header switcher: a bottom sheet listing every car with its telemetry, ACTIVE marked. */
    void showGarageSheet() {
        List<VehicleSummary> cars = garageVehicles(lastStatus);
        if (cars == null || cars.size() < 2) return;

        View content = LayoutInflater.from(this).inflate(R.layout.sheet_garage, null);
        LinearLayout rows = content.findViewById(R.id.layoutGarageSheetRows);
        TextView subtitle = content.findViewById(R.id.tvGarageSheetSubtitle);
        TextView fetched = content.findViewById(R.id.tvGarageSheetFetched);
        Button refresh = content.findViewById(R.id.btnGarageSheetRefresh);

        subtitle.setText(getResources().getQuantityString(
                R.plurals.garage_sheet_sub, cars.size(), cars.size()));

        long fetchedAt = configStore != null ? configStore.getVehiclesFetchedAt() : 0L;
        fetched.setText(fetchedAt > 0L
                ? getString(R.string.garage_meta_fmt, garageTimeFormat.format(new Date(fetchedAt)),
                        displayNameForVin(cars, currentActiveVin()))
                : getString(R.string.garage_meta_no_time, displayNameForVin(cars, currentActiveVin())));

        final BottomSheetDialog dialog = new BottomSheetDialog(this);
        String activeVin = currentActiveVin();
        if (rows != null) {
            rows.removeAllViews();
            for (VehicleSummary car : cars) {
                if (car == null) continue;
                View row = buildGarageRow(rows, car, activeVin, false);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    switchToGarageVehicle(car);
                });
                rows.addView(row);
            }
        }
        if (refresh != null) {
            refresh.setOnClickListener(v -> {
                dialog.dismiss();
                executeWatchAction(WatchProtocol.ACTION_STATUS, -1);
            });
        }
        dialog.setContentView(content);
        dialog.show();
    }

    private String currentActiveVin() {
        if (lastStatus != null && lastStatus.vin != null && !lastStatus.vin.trim().isEmpty()) {
            return lastStatus.vin.trim();
        }
        return configStore != null && configStore.getVin() != null ? configStore.getVin().trim() : "";
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

    private void initAppUpdater(BydWatchApp app) {
        if (app == null) return;
        AppUpdateChecker checker = app.getAppUpdateChecker();
        updateUiHelper = new AppUpdateUiHelper(this, configStore, checker, new AppUpdateUiHelper.Host() {
            @Override
            public void onUpdateCheckStarted(boolean manual) {
                if (btnCheckAppUpdate != null) {
                    btnCheckAppUpdate.setEnabled(false);
                }
                if (tvAppUpdateStatus != null && manual) {
                    tvAppUpdateStatus.setText(R.string.update_status_checking);
                }
            }

            @Override
            public void onUpdateCheckFinished(
                    AppUpdateInfo latestInfo,
                    boolean isNewer,
                    String errorMessage,
                    boolean manual) {
                if (btnCheckAppUpdate != null) {
                    btnCheckAppUpdate.setEnabled(true);
                }
                if (errorMessage != null) {
                    if (tvAppUpdateStatus != null && manual) {
                        tvAppUpdateStatus.setText(
                                getString(R.string.update_status_failed_fmt, errorMessage));
                    }
                    return;
                }
                if (latestInfo != null && isNewer) {
                    availableUpdateInfo = latestInfo;
                    updateAppBannerVisibility();
                    if (tvAppUpdateStatus != null) {
                        if (latestInfo.hasApkAsset()) {
                            String sizeStr = android.text.format.Formatter.formatShortFileSize(
                                    MainActivity.this, latestInfo.apkSizeBytes);
                            tvAppUpdateStatus.setText(getString(
                                    R.string.update_status_available_fmt,
                                    latestInfo.versionName,
                                    sizeStr));
                        } else {
                            tvAppUpdateStatus.setText(getString(
                                    R.string.update_status_no_apk_fmt,
                                    latestInfo.versionName));
                        }
                    }
                } else {
                    availableUpdateInfo = null;
                    updateAppBannerVisibility();
                    if (tvAppUpdateStatus != null) {
                        tvAppUpdateStatus.setText(getString(
                                R.string.update_status_up_to_date,
                                com.bydwatch.companion.BuildConfig.VERSION_NAME));
                    }
                }
            }

            @Override
            public void openExternalUrl(String url) {
                MainActivity.this.openExternalUrl(url);
            }

            @Override
            public void showToast(String message, boolean isError) {
                MainActivity.this.showToast(message, isError);
            }

            @Override
            public void onRequestInstallPermission(Intent settingsIntent) {
                try {
                    installPermissionLauncher.launch(settingsIntent);
                } catch (Exception e) {
                    MainActivity.this.showToast("Unable to open settings", true);
                }
            }
        });

        boolean isRobolectric = "robolectric".equalsIgnoreCase(Build.FINGERPRINT);
        if (!isRobolectric || BydWatchApp.hasTestAppUpdateChecker()) {
            updateUiHelper.maybeAutoCheckForUpdates();
        }
    }

    private void updateAppBannerVisibility() {
        if (bannerAppUpdate == null) return;
        boolean show = currentPageId == R.id.nav_controls && availableUpdateInfo != null;
        bannerAppUpdate.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && tvUpdateBannerTitle != null) {
            tvUpdateBannerTitle.setText(getString(
                    R.string.update_banner_title_fmt,
                    availableUpdateInfo.versionName));
        }
    }

    @VisibleForTesting
    AppUpdateUiHelper getUpdateUiHelperForTesting() {
        return updateUiHelper;
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
