package com.bydwatch.companion.ui;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.appcompat.app.AlertDialog;
import android.view.View;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.wear.WearBridgeService;
import com.bydwatch.companion.wear.WearEngineBridge;
import com.bydwatch.companion.wear.WearEngineBridgeTest;
import com.bydwatch.companion.wear.WearEngineConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class MainActivityTest {

    public static class TestGateway extends BydRepositoryTest.FakeBydGateway {
        public final AtomicInteger fetchVinCalls = new AtomicInteger();
        public String returnedVin = "BYDATTO1TESTVIN99";

        @Override
        public String fetchFirstVin() throws Exception {
            fetchVinCalls.incrementAndGet();
            if (!authValid) {
                throw new java.io.IOException("Login failed: unauthorized");
            }
            return returnedVin;
        }
    }

    private BydRepositoryTest.InMemoryConfigStore configStore;
    private TestGateway fakeGateway;
    private BydRepository repository;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    private void awaitAsyncWork() {
        try {
            // Drain background work on the repository's real single-thread executor
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Async work timed out or failed", e);
        }
        // Drain posted runnables on the Robolectric main looper
        ShadowLooper.idleMainLooper();
    }

    @Before
    public void setUp() {
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("testuser@byd.id");
        configStore.setPassword("ValidPass123");
        configStore.setPin("123456");
        configStore.setVin("LC0TESTVIN1234567");
        configStore.setRegion("id");
        configStore.setDefaultTempC(17);

        fakeGateway = new TestGateway();

        // Use real single-threaded background executor - tests will fail if views are touched off main thread
        repository = new BydRepository(fakeGateway, configStore);
        BydWatchApp.setTestDependencies(configStore, fakeGateway, repository);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        awaitAsyncWork();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.destroy();
            } catch (Exception ignored) {}
            controller = null;
        }
        if (repository != null) {
            repository.shutdown();
        }
        BydWatchApp.resetForTesting();
    }

    @Test
    public void displaysAlternateAuthNoticeAndDefault17CTemp() {
        TextView tempView = activity.findViewById(R.id.tvTargetTemp);
        TextView authNotice = activity.findViewById(R.id.tvAuthNotice);

        assertNotNull(tempView);
        assertNotNull(authNotice);
        assertEquals("17°C", tempView.getText().toString());
        assertTrue(authNotice.getText().toString().contains("Alternate Vehicle Authorization"));
    }

    @Test
    public void tempButtonsClampBetween17And31() {
        TextView tempView = activity.findViewById(R.id.tvTargetTemp);
        Button btnMinus = activity.findViewById(R.id.btnTempMinus);
        Button btnPlus = activity.findViewById(R.id.btnTempPlus);

        assertEquals("17°C", tempView.getText().toString());

        // Clamped at lower bound 17
        btnMinus.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("17°C", tempView.getText().toString());

        // Increment to 19
        btnPlus.performClick();
        btnPlus.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("19°C", tempView.getText().toString());

        // Increment past upper bound 31
        for (int i = 0; i < 20; i++) {
            btnPlus.performClick();
        }
        ShadowLooper.idleMainLooper();
        assertEquals("31°C", tempView.getText().toString());

        // Decrement from 31
        btnMinus.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("30°C", tempView.getText().toString());
    }

    @Test
    public void savesCredentialsToConfigStoreWhenValid() {
        EditText etUsername = activity.findViewById(R.id.etUsername);
        EditText etPassword = activity.findViewById(R.id.etPassword);
        EditText etPin = activity.findViewById(R.id.etPin);
        EditText etVin = activity.findViewById(R.id.etVin);
        Button btnSave = activity.findViewById(R.id.btnSaveCredentials);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        etUsername.setText("newowner@byd.id");
        etPassword.setText("NewPassword99");
        etPin.setText("654321");
        etVin.setText("VIN9876543210");

        btnSave.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("newowner@byd.id", configStore.getUsername());
        assertEquals("NewPassword99", configStore.getPassword());
        assertEquals("654321", configStore.getPin());
        assertEquals("VIN9876543210", configStore.getVin());
        assertTrue(tvLog.getText().toString().contains("saved") || tvLog.getText().toString().contains("Saved"));
    }

    @Test
    public void saveCredentialsRejectsMissingCredentialsOrInvalidPin() {
        EditText etUsername = activity.findViewById(R.id.etUsername);
        EditText etPassword = activity.findViewById(R.id.etPassword);
        EditText etPin = activity.findViewById(R.id.etPin);
        Button btnSave = activity.findViewById(R.id.btnSaveCredentials);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        // Test non-6-digit PIN
        etPin.setText("12345");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(tvLog.getText().toString().contains("Validation error"));
        assertFalse("12345".equals(configStore.getPin()));

        // Test empty username
        etUsername.setText("");
        etPin.setText("123456");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(tvLog.getText().toString().contains("Validation error"));

        // Test empty password when no password is saved in ConfigStore
        configStore.setPassword(null);
        tvLog.setText("");
        etUsername.setText("valid@user.com");
        etPassword.setText("");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(tvLog.getText().toString().contains("Validation error"));
    }

    @Test
    public void fetchVinAndTestLoginRejectsInvalidInputs() {
        EditText etPin = activity.findViewById(R.id.etPin);
        Button btnFetchVin = activity.findViewById(R.id.btnFetchVin);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        etPin.setText("notdigits");
        btnFetchVin.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(btnFetchVin.isEnabled());
        assertEquals(0, fakeGateway.loginCalls.get());
        assertTrue(tvLog.getText().toString().contains("Validation error"));
    }

    @Test
    public void fetchVinAndTestLoginUpdatesVinAndRefreshesStatus() {
        Button btnFetchVin = activity.findViewById(R.id.btnFetchVin);
        EditText etVin = activity.findViewById(R.id.etVin);
        TextView tvSoc = activity.findViewById(R.id.tvSoc);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnFetchVin.performClick();
        // Stage 1: fetchFirstVin background work + onSuccess post to main looper
        awaitAsyncWork();
        // Stage 2: executeWatchAction(status) background work + response post to main looper
        awaitAsyncWork();

        assertTrue(btnFetchVin.isEnabled());
        assertTrue(fakeGateway.loginCalls.get() >= 1);
        assertTrue(fakeGateway.fetchVinCalls.get() >= 1);
        assertEquals("BYDATTO1TESTVIN99", etVin.getText().toString());
        assertEquals("BYDATTO1TESTVIN99", configStore.getVin());
        assertTrue(tvSoc.getText().toString().contains("80"));
        assertTrue(tvLog.getText().toString().contains("VIN") || tvLog.getText().toString().contains("Login"));
    }

    @Test
    public void fetchVinHandlesLoginOrFetchErrorCleanly() {
        Button btnFetchVin = activity.findViewById(R.id.btnFetchVin);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        fakeGateway.authValid = false;

        btnFetchVin.performClick();
        awaitAsyncWork();

        assertTrue(btnFetchVin.isEnabled());
        assertTrue(tvLog.getText().toString().contains("failed") || tvLog.getText().toString().contains("Login"));
    }

    @Test
    public void lockButtonSendsLockCommandAndUpdatesUI() {
        Button btnLock = activity.findViewById(R.id.btnLock);
        TextView tvLockState = activity.findViewById(R.id.tvLockState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnLock.performClick();
        awaitAsyncWork();

        assertEquals(1, fakeGateway.lockCalls.get());
        assertEquals("Locked", tvLockState.getText().toString());
        assertTrue(tvLog.getText().toString().contains("Lock") || tvLog.getText().toString().contains("Locked"));
    }

    @Test
    public void unlockButtonSendsUnlockCommandAndUpdatesUI() {
        Button btnUnlock = activity.findViewById(R.id.btnUnlock);
        TextView tvLockState = activity.findViewById(R.id.tvLockState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnUnlock.performClick();
        awaitAsyncWork();

        assertEquals(1, fakeGateway.unlockCalls.get());
        assertEquals("Unlocked", tvLockState.getText().toString());
        assertTrue(tvLog.getText().toString().contains("Unlock") || tvLog.getText().toString().contains("Unlocked"));
    }

    @Test
    public void acOnButtonSendsAcOnCommandWithTargetTemp() {
        Button btnPlus = activity.findViewById(R.id.btnTempPlus);
        btnPlus.performClick(); // 18°C
        btnPlus.performClick(); // 19°C
        ShadowLooper.idleMainLooper();

        Button btnAcOn = activity.findViewById(R.id.btnAcOn);
        TextView tvAcState = activity.findViewById(R.id.tvAcState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnAcOn.performClick();
        awaitAsyncWork();

        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals("AC On", tvAcState.getText().toString());
        assertTrue(tvLog.getText().toString().contains("AC") || tvLog.getText().toString().contains("started"));
    }

    @Test
    public void acOffButtonSendsAcOffCommand() {
        Button btnAcOff = activity.findViewById(R.id.btnAcOff);
        TextView tvAcState = activity.findViewById(R.id.tvAcState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnAcOff.performClick();
        awaitAsyncWork();

        assertEquals(1, fakeGateway.stopClimateCalls.get());
        assertEquals("AC Off", tvAcState.getText().toString());
        assertTrue(tvLog.getText().toString().contains("AC") || tvLog.getText().toString().contains("stopped"));
    }

    @Test
    public void refreshButtonFetchesRealtimeStatus() {
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);
        TextView tvSoc = activity.findViewById(R.id.tvSoc);
        TextView tvRange = activity.findViewById(R.id.tvRange);

        fakeGateway.currentStatus = new VehicleStatus(95, 380, true, false, 23, 17, System.currentTimeMillis());

        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals("95%", tvSoc.getText().toString());
        assertEquals("380 km", tvRange.getText().toString());
    }

    @Test
    public void flashLightsAndHornTriggerCommands() {
        Button btnLights = activity.findViewById(R.id.btnLights);
        Button btnHorn = activity.findViewById(R.id.btnHorn);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnLights.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.flashLightsCalls.get());

        btnHorn.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.findCarCalls.get());

        assertTrue(tvLog.getText().toString().contains("Lights") || tvLog.getText().toString().contains("flashed"));
        assertTrue(tvLog.getText().toString().contains("Horn") || tvLog.getText().toString().contains("sounded"));
    }

    @Test
    public void handlesErrorResponseGracefullyWithoutCrashing() {
        Button btnLock = activity.findViewById(R.id.btnLock);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        fakeGateway.pinValid = false;

        btnLock.performClick();
        awaitAsyncWork();

        assertTrue(tvLog.getText().toString().contains("failed [PIN_INVALID]"));
    }

    @Test
    public void clampsOutOfRangePersistedDefaultTempOnStartup() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        configStore.setDefaultTempC(50); // Out of range (> 31)

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity newActivity = controller.get();

        TextView tempView = newActivity.findViewById(R.id.tvTargetTemp);
        assertEquals("17°C", tempView.getText().toString());
    }

    @Test
    public void watchStatusReflectsConfigurationState() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        configStore.clear();

        WearEngineBridgeTest.FakePeerTransport readyTransport = new WearEngineBridgeTest.FakePeerTransport();
        readyTransport.permissionGranted = true;
        WearEngineBridge readyBridge = new WearEngineBridge(repository, readyTransport);
        BydWatchApp.setTestWearDependencies(new WearEngineConfig(), readyTransport, readyBridge);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity newActivity = controller.get();
        TextView watchStatus = newActivity.findViewById(R.id.tvWatchStatus);
        assertEquals(newActivity.getString(R.string.watch_status_unconfigured), watchStatus.getText().toString());

        // Configure credentials and save
        EditText etUsername = newActivity.findViewById(R.id.etUsername);
        EditText etPassword = newActivity.findViewById(R.id.etPassword);
        EditText etPin = newActivity.findViewById(R.id.etPin);
        EditText etVin = newActivity.findViewById(R.id.etVin);
        Button btnSave = newActivity.findViewById(R.id.btnSaveCredentials);

        etUsername.setText("configured@byd.id");
        etPassword.setText("Pass1234");
        etPin.setText("123456");
        etVin.setText("VIN12345678");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(newActivity.getString(R.string.watch_status_ready), watchStatus.getText().toString());
    }

    @Test
    @Config(sdk = 34)
    public void bridgeCardControlsInteractCorrectly() {
        if (controller != null) {
            controller.destroy();
            controller = null;
        }

        WearEngineBridgeTest.FakePeerTransport fakeTransport = new WearEngineBridgeTest.FakePeerTransport();
        fakeTransport.permissionGranted = false;
        WearEngineBridge fakeBridge = new WearEngineBridge(repository, fakeTransport);
        BydWatchApp.setTestWearDependencies(new WearEngineConfig(), fakeTransport, fakeBridge);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity newActivity = controller.get();

        Button btnAuth = newActivity.findViewById(R.id.btnAuthHealth);
        Button btnToggle = newActivity.findViewById(R.id.btnToggleService);
        Button btnBattery = newActivity.findViewById(R.id.btnBatteryOpt);
        TextView tvWatch = newActivity.findViewById(R.id.tvWatchStatus);
        TextView tvService = newActivity.findViewById(R.id.tvServiceStatus);

        assertNotNull(btnAuth);
        assertNotNull(btnToggle);
        assertNotNull(btnBattery);
        assertTrue(btnAuth.isEnabled());
        assertEquals(newActivity.getString(R.string.btn_start_service), btnToggle.getText().toString());
        assertEquals(newActivity.getString(R.string.service_status_stopped), tvService.getText().toString());
        assertEquals(newActivity.getString(R.string.status_health_needed), tvWatch.getText().toString());

        // 1. Test Health authorization request
        btnAuth.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(btnAuth.isEnabled());

        fakeTransport.simulatePermissionConsent();
        ShadowLooper.idleMainLooper();
        assertTrue(btnAuth.isEnabled());
        assertEquals(newActivity.getString(R.string.watch_status_ready), tvWatch.getText().toString());

        // 2. Test Battery optimization button click
        btnBattery.performClick();
        ShadowLooper.idleMainLooper();

        // 3. Test Service toggle button click on API 34 without BLUETOOTH_CONNECT
        ShadowActivity shadowActivity = Shadows.shadowOf(newActivity);
        ShadowApplication shadowApp = Shadows.shadowOf((Application) newActivity.getApplication());

        btnToggle.performClick();
        ShadowLooper.idleMainLooper();

        ShadowActivity.PermissionsRequest req = shadowActivity.getLastRequestedPermission();
        assertNotNull(req);
        assertTrue(java.util.Arrays.asList(req.requestedPermissions).contains(Manifest.permission.BLUETOOTH_CONNECT));

        // 4. Simulate user granting BLUETOOTH_CONNECT
        newActivity.onRequestPermissionsResult(
                1001,
                new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                new int[]{PackageManager.PERMISSION_GRANTED}
        );
        ShadowLooper.idleMainLooper();

        Intent startedServiceIntent = shadowApp.getNextStartedService();
        assertNotNull(startedServiceIntent);
        assertEquals(WearBridgeService.class.getName(), startedServiceIntent.getComponent().getClassName());
        assertEquals(WearBridgeService.ACTION_START, startedServiceIntent.getAction());

        // Simulate service running
        ServiceController<WearBridgeService> serviceController = Robolectric.buildService(WearBridgeService.class, startedServiceIntent).create().startCommand(0, 0);
        WearBridgeService service = serviceController.get();
        ShadowLooper.idleMainLooper();

        assertEquals(newActivity.getString(R.string.service_status_running), tvService.getText().toString());
        assertEquals(newActivity.getString(R.string.btn_stop_service), btnToggle.getText().toString());
        assertEquals(newActivity.getString(R.string.status_bridge_listening), tvWatch.getText().toString());

        // 5. Test toggling the service off
        btnToggle.performClick();
        ShadowLooper.idleMainLooper();

        Intent stopServiceIntent = shadowApp.getNextStartedService();
        assertNotNull(stopServiceIntent);
        assertEquals(WearBridgeService.ACTION_STOP, stopServiceIntent.getAction());

        serviceController.destroy();
    }

    @Test
    public void apkWorksStandaloneWithoutWatchIntegration() throws Exception {
        BydWatchApp.setTestWearDependencies(new WearEngineConfig(), null, null);

        if (controller != null) {
            controller.destroy();
            controller = null;
        }

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity newActivity = controller.get();

        Button btnRefresh = newActivity.findViewById(R.id.btnRefresh);
        Button btnLock = newActivity.findViewById(R.id.btnLock);
        Button btnUnlock = newActivity.findViewById(R.id.btnUnlock);
        Button btnAcOn = newActivity.findViewById(R.id.btnAcOn);
        Button btnAcOff = newActivity.findViewById(R.id.btnAcOff);
        Button btnLights = newActivity.findViewById(R.id.btnLights);
        Button btnHorn = newActivity.findViewById(R.id.btnHorn);

        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.fetchRealtimeCalls.get());

        btnLock.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.lockCalls.get());

        btnUnlock.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.unlockCalls.get());

        btnAcOn.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.startClimateCalls.get());

        btnAcOff.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.stopClimateCalls.get());

        btnLights.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.flashLightsCalls.get());

        btnHorn.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.findCarCalls.get());
    }

    @Test
    public void savedCredentialsStayHiddenAndPreservedWhenBlankOnUpdate() {
        EditText etPassword = activity.findViewById(R.id.etPassword);
        EditText etPin = activity.findViewById(R.id.etPin);
        EditText etVin = activity.findViewById(R.id.etVin);
        Button btnSave = activity.findViewById(R.id.btnSaveCredentials);

        // Pre-configured secrets from @Before must never be pre-filled into visible/editable fields
        assertEquals("", etPassword.getText().toString());
        assertEquals("", etPin.getText().toString());

        // Update VIN while leaving password and PIN blank -> stored secrets stay intact
        String initialPassword = configStore.getPassword();
        String initialPin = configStore.getPin();
        etVin.setText("UPDATEDVIN1234567");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(initialPassword, configStore.getPassword());
        assertEquals(initialPin, configStore.getPin());
        assertEquals("UPDATEDVIN1234567", configStore.getVin());
        assertEquals("", etPassword.getText().toString());
        assertEquals("", etPin.getText().toString());
    }

    @Test
    public void screenshotsAreEnabledAndAccountCollapsesWhenConfigured() {
        int flags = activity.getWindow().getAttributes().flags;
        assertEquals(0, flags & WindowManager.LayoutParams.FLAG_SECURE);

        View layoutFields = activity.findViewById(R.id.layoutAccountFields);
        Button btnToggle = activity.findViewById(R.id.btnToggleAccount);
        assertEquals(View.GONE, layoutFields.getVisibility());

        btnToggle.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, layoutFields.getVisibility());

        btnToggle.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, layoutFields.getVisibility());
    }

    @Test
    public void eventLogUsesNestedScrollViewAndClearButtonWorks() {
        NestedScrollView scrollLog = activity.findViewById(R.id.scrollStatusLog);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);
        Button btnClear = activity.findViewById(R.id.btnClearLog);

        assertNotNull(scrollLog);
        assertFalse(tvLog.getText().toString().isEmpty());

        btnClear.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(tvLog.getText().toString().contains("Log cleared."));
    }

    @Test
    public void telemetryUpdatesSocProgressBar() {
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);
        ProgressBar pbSoc = activity.findViewById(R.id.pbBatterySoc);
        TextView tvLock = activity.findViewById(R.id.tvLockState);

        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals(80, pbSoc.getProgress());
        assertEquals("Unlocked", tvLock.getText().toString());

        Button btnLock = activity.findViewById(R.id.btnLock);
        btnLock.performClick();
        awaitAsyncWork();
        assertEquals("Locked", tvLock.getText().toString());
    }

    @Test
    public void unlockShowsSuccessOrFailureToastWithoutScrollingPageDown() {
        NestedScrollView rootScroll = activity.findViewById(R.id.rootScroll);
        Button btnUnlock = activity.findViewById(R.id.btnUnlock);

        btnUnlock.performClick();
        awaitAsyncWork();

        assertEquals(0, rootScroll.getScrollY());
        assertEquals("Unlock successful", ShadowToast.getTextOfLatestToast());

        fakeGateway.authValid = false;
        btnUnlock.performClick();
        awaitAsyncWork();

        assertEquals(0, rootScroll.getScrollY());
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Unlock failed"));
    }

    @Test
    public void hidesCabinTempWhenUnavailableAndShowsWhenPresent() {
        View layoutCabinTemp = activity.findViewById(R.id.layoutCabinTemp);
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);

        // Starts hidden when no cabin temp telemetry exists yet
        assertEquals(View.GONE, layoutCabinTemp.getVisibility());

        // Present when gateway returns cabinTempC
        fakeGateway.currentStatus = new VehicleStatus(78, 280, true, false, 24, 17, 1700000000L);
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals(View.VISIBLE, layoutCabinTemp.getVisibility());

        // Hidden again if subsequent telemetry omits cabinTempC
        fakeGateway.currentStatus = new VehicleStatus(78, 280, true, false, null, 17, 1700000005L);
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals(View.GONE, layoutCabinTemp.getVisibility());
    }

    @Test
    public void regionSelectionPersistsAndUnitsToggleCorrectly() {
        AutoCompleteTextView actvRegion = activity.findViewById(R.id.actvRegion);
        EditText etCountryCode = activity.findViewById(R.id.etCountryCode);
        EditText etUsername = activity.findViewById(R.id.etUsername);
        EditText etPassword = activity.findViewById(R.id.etPassword);
        EditText etPin = activity.findViewById(R.id.etPin);
        Button btnSave = activity.findViewById(R.id.btnSaveCredentials);

        actvRegion.setText("China Mainland (CN)", false);
        etCountryCode.setText("cn");
        etUsername.setText("driver@byd.cn");
        etPassword.setText("Pass12345");
        etPin.setText("654321");
        btnSave.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("cn", configStore.getRegion());
        assertEquals("CN", configStore.getCountryCode());

        // Unit cycling updates distance, temperature, and TPMS pressure displays
        Button btnUnitDist = activity.findViewById(R.id.btnUnitDist);
        Button btnUnitTemp = activity.findViewById(R.id.btnUnitTemp);
        Button btnUnitPres = activity.findViewById(R.id.btnUnitPres);
        TextView tvRange = activity.findViewById(R.id.tvRange);
        TextView tvTpmsLf = activity.findViewById(R.id.tvTpmsLf);
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);

        fakeGateway.currentStatus = new VehicleStatus.Builder(80, 100, true, false, 20, 17, 1700000000L)
                .tirePressuresKpa(250, 250, 240, 240)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals("100 km", tvRange.getText().toString());
        assertEquals("LF 250 kPa", tvTpmsLf.getText().toString());

        btnUnitDist.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("62 mi", tvRange.getText().toString());

        btnUnitPres.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("LF 36.3 PSI", tvTpmsLf.getText().toString());

        btnUnitPres.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("LF 2.50 bar", tvTpmsLf.getText().toString());

        btnUnitTemp.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("°F", btnUnitTemp.getText().toString());
    }

    @Test
    public void widgetSyncFrequencySettingSavesMinutesAndHoursWithValidation() {
        TextView tvLabel = activity.findViewById(R.id.tvSyncIntervalLabel);
        EditText etInterval = activity.findViewById(R.id.etSyncInterval);
        Button btnUnit = activity.findViewById(R.id.btnSyncIntervalUnit);
        Button btnApply = activity.findViewById(R.id.btnApplySyncInterval);

        assertEquals("30", etInterval.getText().toString());
        assertEquals("Min", btnUnit.getText().toString());
        assertEquals("Widget Auto-Sync (30 min)", tvLabel.getText().toString());

        // Switch to Hours and save 2 hours -> 120 minutes
        btnUnit.performClick();
        assertEquals("Hr", btnUnit.getText().toString());
        etInterval.setText("2");
        btnApply.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(120, configStore.getWidgetSyncIntervalMinutes());
        assertEquals("Widget Auto-Sync (2 hr)", tvLabel.getText().toString());

        // Reject > 24 hours
        etInterval.setText("25");
        btnApply.performClick();
        assertNotNull(etInterval.getError());
        assertEquals(120, configStore.getWidgetSyncIntervalMinutes());

        // Switch back to Min and save 10 minutes
        btnUnit.performClick();
        assertEquals("Min", btnUnit.getText().toString());
        etInterval.setText("10");
        btnApply.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(10, configStore.getWidgetSyncIntervalMinutes());
        assertEquals("Widget Auto-Sync (10 min)", tvLabel.getText().toString());
    }

    @Test
    public void carLocationCardDisplaysCoordinatesAndPromptsNavigationChooserOnClick() {
        View cardLocation = activity.findViewById(R.id.cardLocation);
        TextView tvLocation = activity.findViewById(R.id.tvCarLocation);
        TextView tvHint = activity.findViewById(R.id.tvCarLocationHint);
        Button btnRefreshLoc = activity.findViewById(R.id.btnRefreshLocation);

        fakeGateway.currentLocation = new com.bydwatch.companion.data.BydGateway.VehicleLocation(
                -6.20880, 106.84560, 1700000123L);
        btnRefreshLoc.performClick();
        awaitAsyncWork();

        assertEquals("-6.20880, 106.84560", tvLocation.getText().toString());
        assertEquals(activity.getString(R.string.label_location_tap_nav), tvHint.getText().toString());

        cardLocation.performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = (AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("Clicking location card should show navigation confirmation dialog", dialog);
        assertTrue(dialog.isShowing());
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("Confirming navigation dialog should open app chooser", launched);
        assertEquals(Intent.ACTION_CHOOSER, launched.getAction());
    }

    @Test
    public void showsChargingBadgeAndRemainingTimeWhenVehicleIsCharging() {
        TextView tvChargingBadge = activity.findViewById(R.id.tvChargingBadge);
        TextView tvOpeningBadge = activity.findViewById(R.id.tvOpeningBadge);
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);

        fakeGateway.currentStatus = new VehicleStatus.Builder(65, 260, true, false, 24, 17, 1700000000L)
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargeRemainingMinutes(95)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals(View.VISIBLE, tvChargingBadge.getVisibility());
        assertEquals("⚡ Charging · 1h 35m left", tvChargingBadge.getText().toString());
        assertEquals(View.VISIBLE, tvOpeningBadge.getVisibility());
        assertEquals("All Closed", tvOpeningBadge.getText().toString());

        fakeGateway.currentStatus = new VehicleStatus.Builder(100, 400, true, false, 24, 17, 1700000100L)
                .chargingState(VehicleStatus.CHARGING_CONNECTED)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals(View.VISIBLE, tvChargingBadge.getVisibility());
        assertEquals("Plugged In", tvChargingBadge.getText().toString());
    }

    @Test
    public void hideExtraControlsToggleHidesAndRestoresWindowAndHeatRows() {
        View row1 = activity.findViewById(R.id.layoutComfortRow1);
        View row2 = activity.findViewById(R.id.layoutComfortRow2);
        androidx.appcompat.widget.SwitchCompat toggle = activity.findViewById(R.id.switchHideExtraControls);

        assertEquals(View.VISIBLE, row1.getVisibility());
        assertEquals(View.VISIBLE, row2.getVisibility());
        assertFalse(toggle.isChecked());

        toggle.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(configStore.isHideExtraControls());
        assertEquals(View.GONE, row1.getVisibility());
        assertEquals(View.GONE, row2.getVisibility());

        toggle.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(configStore.isHideExtraControls());
        assertEquals(View.VISIBLE, row1.getVisibility());
        assertEquals(View.VISIBLE, row2.getVisibility());
    }

    @Test
    public void appendsDiscoveredCarModelAndShowsChargeKwAndHoursMinutes() {
        TextView tvHeaderTitle = activity.findViewById(R.id.tvHeaderTitle);
        TextView tvControlsTitle = activity.findViewById(R.id.tvControlsTitle);
        TextView tvChargingBadge = activity.findViewById(R.id.tvChargingBadge);
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);

        assertEquals("BYD EV", tvHeaderTitle.getText().toString());
        assertEquals("BYD EV Controls", tvControlsTitle.getText().toString());

        fakeGateway.currentStatus = new VehicleStatus.Builder(68, 280, true, false, 24, 17, 1700000200L)
                .vin("LC0TESTVIN1234567")
                .modelName("Atto 3")
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargeRemainingMinutes(45)
                .chargePowerKw(7.2)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals("BYD Atto 3", tvHeaderTitle.getText().toString());
        assertEquals("BYD Atto 3 Controls", tvControlsTitle.getText().toString());
        assertEquals("⚡ Charging · 7.2 kW · 0h 45m left", tvChargingBadge.getText().toString());

        fakeGateway.currentStatus = new VehicleStatus.Builder(80, 320, true, false, 24, 17, 1700000300L)
                .vin("LC0TESTVIN1234567")
                .modelName("BYD Seal")
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargeRemainingMinutes(120)
                .chargePowerKw(60.5)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals("BYD Seal", tvHeaderTitle.getText().toString());
        assertEquals("BYD Seal Controls", tvControlsTitle.getText().toString());
        assertEquals("⚡ Charging · 60.5 kW · 2h 0m left", tvChargingBadge.getText().toString());

        fakeGateway.currentStatus = new VehicleStatus.Builder(75, 227, true, false, 24, 17, 1700000400L)
                .vin("LC0TESTVIN1234567")
                .modelName("SEAGULL/BYD AT")
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();

        assertEquals("SEAGULL/BYD AT", tvHeaderTitle.getText().toString());
        assertEquals("SEAGULL/BYD AT Controls", tvControlsTitle.getText().toString());

        EditText etCustomModelName = activity.findViewById(R.id.etCustomModelName);
        Button btnSaveCustomModel = activity.findViewById(R.id.btnSaveCustomModel);
        etCustomModelName.setText("BYD Atto 1");
        btnSaveCustomModel.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("BYD Atto 1", configStore.getCustomVehicleName());
        assertEquals("BYD Atto 1", tvHeaderTitle.getText().toString());
        assertEquals("BYD Atto 1 Controls", tvControlsTitle.getText().toString());

        etCustomModelName.setText("");
        btnSaveCustomModel.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("", configStore.getCustomVehicleName());
        assertEquals("SEAGULL/BYD AT", tvHeaderTitle.getText().toString());
        assertEquals("SEAGULL/BYD AT Controls", tvControlsTitle.getText().toString());
    }

    @Test
    public void hamburgerDrawerSwitchesFourPagesAndCopyLogCopiesAllText() {
        View cardControls = activity.findViewById(R.id.cardControls);
        View cardAccount = activity.findViewById(R.id.cardAccount);
        View cardPreferences = activity.findViewById(R.id.cardPreferences);
        View cardBridge = activity.findViewById(R.id.cardBridge);
        com.google.android.material.navigation.NavigationView navView = activity.findViewById(R.id.navView);
        androidx.drawerlayout.widget.DrawerLayout drawer = activity.findViewById(R.id.drawerLayout);
        View btnHamburger = activity.findViewById(R.id.btnHamburger);

        assertEquals(View.VISIBLE, cardControls.getVisibility());
        assertEquals(View.GONE, cardAccount.getVisibility());
        assertEquals(View.GONE, cardPreferences.getVisibility());
        assertEquals(View.GONE, cardBridge.getVisibility());

        btnHamburger.performClick();
        for (int i = 0; i < 30 && !drawer.isDrawerOpen(androidx.core.view.GravityCompat.START); i++) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS);
            drawer.computeScroll();
        }
        assertTrue(drawer.isDrawerOpen(androidx.core.view.GravityCompat.START));

        assertFalse(containsTextViewWithText(cardControls, "SoC"));

        navView.getMenu().performIdentifierAction(R.id.nav_account, 0);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, cardAccount.getVisibility());
        assertEquals(View.GONE, cardControls.getVisibility());
        assertEquals(View.GONE, cardPreferences.getVisibility());
        assertEquals(View.GONE, cardBridge.getVisibility());

        navView.getMenu().performIdentifierAction(R.id.nav_preferences, 0);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, cardPreferences.getVisibility());
        assertEquals(View.GONE, cardAccount.getVisibility());

        navView.getMenu().performIdentifierAction(R.id.nav_watch_bridge, 0);
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, cardBridge.getVisibility());
        assertEquals(View.GONE, cardPreferences.getVisibility());

        Button btnCopyLog = activity.findViewById(R.id.btnCopyLog);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);
        btnCopyLog.performClick();
        ShadowLooper.idleMainLooper();

        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertNotNull(cm.getPrimaryClip());
        assertEquals(tvLog.getText().toString(), cm.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("Logs copied to clipboard", ShadowToast.getTextOfLatestToast());

        Button btnRefresh = activity.findViewById(R.id.btnRefresh);
        TextView tvChargingBadge = activity.findViewById(R.id.tvChargingBadge);
        fakeGateway.currentStatus = new VehicleStatus.Builder(82, 234, false, true, 22, 17, 1700000500L)
                .vin("LC0TESTVIN1234567")
                .modelName("Atto 1")
                .chargingState(VehicleStatus.CHARGING_NONE)
                .vehicleOn(true)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals("Driving / On", tvChargingBadge.getText().toString());
    }

    private static boolean containsTextViewWithText(View view, String target) {
        if (view instanceof TextView && target.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsTextViewWithText(group.getChildAt(i), target)) {
                    return true;
                }
            }
        }
        return false;
    }
}
