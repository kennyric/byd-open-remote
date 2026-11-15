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
import com.bydwatch.companion.data.ConfigStore;
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
import org.robolectric.shadows.ShadowDialog;
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
        TextView presetView = activity.findViewById(R.id.tvAcTogglePreset);
        TextView toggleTitle = activity.findViewById(R.id.tvAcToggleTitle);
        TextView authNotice = activity.findViewById(R.id.tvAuthNotice);

        assertNotNull(presetView);
        assertNotNull(toggleTitle);
        assertNotNull(authNotice);
        assertEquals("Turn AC On", toggleTitle.getText().toString());
        assertEquals("17°C", presetView.getText().toString());
        assertTrue(authNotice.getText().toString().contains("Alternate Vehicle Authorization"));
    }

    @Test
    public void tempButtonsClampBetween17And31() {
        View btnAdjust = activity.findViewById(R.id.btnAcAdjust);
        btnAdjust.performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        TextView tempView = dialog.findViewById(R.id.tvTargetTemp);
        Button btnMinus = dialog.findViewById(R.id.btnTempMinus);
        Button btnPlus = dialog.findViewById(R.id.btnTempPlus);

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
        assertEquals("30°C", ((TextView) activity.findViewById(R.id.tvAcTogglePreset)).getText().toString());
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
        activity.findViewById(R.id.btnAcAdjust).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        Button btnPlus = dialog.findViewById(R.id.btnTempPlus);
        btnPlus.performClick(); // 18°C
        btnPlus.performClick(); // 19°C
        dialog.findViewById(R.id.btnClimateDone).performClick();
        ShadowLooper.idleMainLooper();

        View btnAcToggle = activity.findViewById(R.id.btnAcToggle);
        TextView tvAcState = activity.findViewById(R.id.tvAcState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnAcToggle.performClick();
        awaitAsyncWork();

        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals(19.0, fakeGateway.lastTempCelsius, 0.01);
        assertEquals("AC On", tvAcState.getText().toString());
        assertTrue(tvLog.getText().toString().contains("AC") || tvLog.getText().toString().contains("started"));
    }

    @Test
    public void acOffButtonSendsAcOffCommand() {
        View btnAcToggle = activity.findViewById(R.id.btnAcToggle);
        TextView tvAcState = activity.findViewById(R.id.tvAcState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        // Turn AC On first so quick toggle transitions to AC On, then tap again to turn AC Off
        btnAcToggle.performClick();
        awaitAsyncWork();
        assertEquals("AC On", tvAcState.getText().toString());

        btnAcToggle.performClick();
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

        TextView presetView = newActivity.findViewById(R.id.tvAcTogglePreset);
        assertEquals("17°C", presetView.getText().toString());
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
        View btnAcToggle = newActivity.findViewById(R.id.btnAcToggle);
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

        btnAcToggle.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.startClimateCalls.get());

        btnAcToggle.performClick();
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
    public void carLocationCardDisplaysCoordinatesAndPromptsNavigationChooserOnClick() throws Exception {
        View cardLocation = activity.findViewById(R.id.cardLocation);
        TextView tvLocation = activity.findViewById(R.id.tvCarLocation);
        TextView tvHint = activity.findViewById(R.id.tvCarLocationHint);
        Button btnRefreshLoc = activity.findViewById(R.id.btnRefreshLocation);

        // Falls back gracefully to coordinates when offline/geocoder unavailable
        fakeGateway.currentLocation = new com.bydwatch.companion.data.BydGateway.VehicleLocation(
                -6.20880, 106.84560, 1700000123L);
        btnRefreshLoc.performClick();
        awaitAsyncWork();
        VehicleLocationHelper.awaitExecutorForTesting();
        ShadowLooper.idleMainLooper();

        assertEquals("-6.20880, 106.84560", tvLocation.getText().toString());
        assertEquals(activity.getString(R.string.label_location_tap_nav), tvHint.getText().toString());

        // Replaces coordinates with resolved street name once reverse-geocoded
        VehicleLocationHelper.setTestStreetLookupForTesting((ctx, lat, lng) -> "Jl. Jenderal Sudirman, Menteng");
        fakeGateway.currentLocation = new com.bydwatch.companion.data.BydGateway.VehicleLocation(
                -6.19500, 106.82300, 1700000200L);
        btnRefreshLoc.performClick();
        awaitAsyncWork();
        VehicleLocationHelper.awaitExecutorForTesting();
        ShadowLooper.idleMainLooper();

        assertEquals("Jl. Jenderal Sudirman, Menteng", tvLocation.getText().toString());

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

        androidx.appcompat.widget.SwitchCompat switchVerboseLogs = activity.findViewById(R.id.switchVerboseLogs);
        switchVerboseLogs.setChecked(true);
        ShadowLooper.idleMainLooper();
        assertTrue(configStore.isVerboseLogsEnabled());

        Button btnRefresh = activity.findViewById(R.id.btnRefresh);
        TextView tvChargingBadge = activity.findViewById(R.id.tvChargingBadge);
        fakeGateway.currentStatus = new VehicleStatus.Builder(82, 234, false, true, 22, 17, 1700000500L)
                .vin("LC0TESTVIN1234567")
                .modelName("Atto 1")
                .chargingState(VehicleStatus.CHARGING_NONE)
                .vehicleOn(true)
                .diagSummary("cs=0 conn=1 gear=3 vs=2")
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals("Driving / On", tvChargingBadge.getText().toString());
        assertTrue(tvLog.getText().toString().contains("[raw] cs=0 conn=1 gear=3 vs=2"));
    }

    @Test
    public void splashScreenDisplaysCreatorAndNonCommercialNoticeAndProceedsToMain() {
        try (ActivityController<SplashActivity> splashController =
                     Robolectric.buildActivity(SplashActivity.class).setup()) {
            SplashActivity splash = splashController.get();
            TextView tvCreator = splash.findViewById(R.id.tvSplashCreator);
            TextView tvNoticeBody = splash.findViewById(R.id.tvSplashNoticeBody);
            Button btnContinue = splash.findViewById(R.id.btnSplashContinue);

            assertTrue(tvCreator.getText().toString().contains("Kenny Ricardo"));
            assertTrue(tvNoticeBody.getText().toString().contains("Tidak diperuntukkan untuk jual beli"));

            btnContinue.performClick();
            ShadowLooper.idleMainLooper();

            Intent next = Shadows.shadowOf(splash).getNextStartedActivity();
            assertNotNull(next);
            assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
            assertTrue(splash.isFinishing());
        }
    }

    @Test
    public void aboutSectionInDebugPageDisplaysMakerAndOpensGithubLinks() {
        TextView tvCreator = activity.findViewById(R.id.tvAboutCreator);
        TextView tvNotice = activity.findViewById(R.id.tvAboutNotice);
        Button btnCompanion = activity.findViewById(R.id.btnAboutGithubCompanion);
        Button btnWatch = activity.findViewById(R.id.btnAboutGithubWatch);

        assertTrue(tvCreator.getText().toString().contains("Kenny Ricardo"));
        assertTrue(tvNotice.getText().toString().contains("Tidak diperuntukkan untuk jual beli"));

        btnCompanion.performClick();
        ShadowLooper.idleMainLooper();
        Intent companionIntent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(companionIntent);
        assertEquals(Intent.ACTION_VIEW, companionIntent.getAction());
        assertEquals("https://github.com/kennyric/byd-open-remote", companionIntent.getDataString());

        btnWatch.performClick();
        ShadowLooper.idleMainLooper();
        Intent watchIntent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(watchIntent);
        assertEquals(Intent.ACTION_VIEW, watchIntent.getAction());
        assertEquals("https://github.com/kennyric/byd-watch-gt4", watchIntent.getDataString());
    }

    @Test
    public void changingTempWhileAcOnTriggersDebouncedAcOnCommand() {
        View btnAcToggle = activity.findViewById(R.id.btnAcToggle);
        View btnAcAdjust = activity.findViewById(R.id.btnAcAdjust);
        TextView tvPreset = activity.findViewById(R.id.tvAcTogglePreset);

        btnAcToggle.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals(17.0, fakeGateway.lastTempCelsius, 0.01);

        btnAcAdjust.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        Button btnTempPlus = dialog.findViewById(R.id.btnTempPlus);
        TextView tvTargetTemp = dialog.findViewById(R.id.tvTargetTemp);

        // Two rapid taps while AC is running should debounce into one live update
        btnTempPlus.performClick();
        btnTempPlus.performClick();
        ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
        assertEquals("19°C", tvTargetTemp.getText().toString());
        assertEquals("19°C", tvPreset.getText().toString());
        assertEquals(1, fakeGateway.startClimateCalls.get());

        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS);
        awaitAsyncWork();
        assertEquals(2, fakeGateway.startClimateCalls.get());
        assertEquals(19.0, fakeGateway.lastTempCelsius, 0.01);
        dialog.dismiss();
    }

    @Test
    public void manualAcDisplaysTempGearFanAndVentControlsInPopupAndUpdatesLive() {
        fakeGateway.currentStatus = fakeGateway.currentStatus.toBuilder()
                .manualAc(true)
                .build();
        Button btnRefresh = activity.findViewById(R.id.btnRefresh);
        btnRefresh.performClick();
        awaitAsyncWork();

        TextView tvPreset = activity.findViewById(R.id.tvAcTogglePreset);
        assertEquals("Lvl 3 · Fan 4 · Body", tvPreset.getText().toString());

        activity.findViewById(R.id.btnAcAdjust).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);

        View layoutFan = dialog.findViewById(R.id.layoutFanControl);
        View layoutVent = dialog.findViewById(R.id.layoutVentControl);
        TextView tvTargetTemp = dialog.findViewById(R.id.tvTargetTemp);
        TextView tvFanLevel = dialog.findViewById(R.id.tvFanLevel);
        Button btnTempPlus = dialog.findViewById(R.id.btnTempPlus);
        Button btnTempMinus = dialog.findViewById(R.id.btnTempMinus);
        Button btnFanPlus = dialog.findViewById(R.id.btnFanPlus);
        Button btnVentBodyFeet = dialog.findViewById(R.id.btnVentBodyFeet);
        Button btnDone = dialog.findViewById(R.id.btnClimateDone);

        assertEquals(View.VISIBLE, layoutFan.getVisibility());
        assertEquals(View.VISIBLE, layoutVent.getVisibility());
        assertEquals("Lvl 3", tvTargetTemp.getText().toString());
        assertEquals("Lvl 4", tvFanLevel.getText().toString());

        btnTempPlus.performClick();
        btnFanPlus.performClick();
        btnVentBodyFeet.performClick();
        assertEquals("Lvl 4", tvTargetTemp.getText().toString());
        assertEquals("Lvl 5", tvFanLevel.getText().toString());
        assertEquals("Lvl 4 · Fan 5 · Body+Feet", tvPreset.getText().toString());
        assertEquals(0, fakeGateway.startElectricClimateCalls.get());
        btnDone.performClick();
        ShadowLooper.idleMainLooper();

        // Main screen toggle uses the persisted manual-AC preset (temp gear, fan level, vent mode)
        View btnAcToggle = activity.findViewById(R.id.btnAcToggle);
        btnAcToggle.performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.startElectricClimateCalls.get());
        assertEquals(0, fakeGateway.startClimateCalls.get());
        assertEquals(4, fakeGateway.lastTempGear);
        assertEquals(5, fakeGateway.lastWindLevel);
        assertEquals(ConfigStore.WIND_MODE_BODY_FEET, fakeGateway.lastWindMode);

        // Adjusting temp/fan/vent while AC is ON sends debounced electric climate update
        activity.findViewById(R.id.btnAcAdjust).performClick();
        ShadowLooper.idleMainLooper();
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.findViewById(R.id.btnTempMinus).performClick();
        dialog.findViewById(R.id.btnFanPlus).performClick();
        dialog.findViewById(R.id.btnVentFeet).performClick();
        assertEquals("Lvl 3", ((TextView) dialog.findViewById(R.id.tvTargetTemp)).getText().toString());
        assertEquals("Lvl 6", ((TextView) dialog.findViewById(R.id.tvFanLevel)).getText().toString());

        ShadowLooper.idleMainLooper(800, TimeUnit.MILLISECONDS);
        awaitAsyncWork();
        assertEquals(2, fakeGateway.startElectricClimateCalls.get());
        assertEquals(3, fakeGateway.lastTempGear);
        assertEquals(6, fakeGateway.lastWindLevel);
        assertEquals(ConfigStore.WIND_MODE_FEET, fakeGateway.lastWindMode);
        dialog.dismiss();

        // Switching to a car WITH climate control restores °C display, hides manual fan/vent controls,
        // and routes quick AC toggle back through auto-climate startClimate()
        fakeGateway.currentStatus = new VehicleStatus.Builder(80, 320, true, false, 26, 17, 1700000600L)
                .manualAc(false)
                .build();
        btnRefresh.performClick();
        awaitAsyncWork();
        assertEquals("17°C", tvPreset.getText().toString());

        activity.findViewById(R.id.btnAcAdjust).performClick();
        ShadowLooper.idleMainLooper();
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(View.GONE, dialog.findViewById(R.id.layoutFanControl).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.layoutVentControl).getVisibility());
        assertEquals("17°C", ((TextView) dialog.findViewById(R.id.tvTargetTemp)).getText().toString());
        dialog.findViewById(R.id.btnClimateApply).performClick();
        awaitAsyncWork();
        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals(17.0, fakeGateway.lastTempCelsius, 0.01);
    }

    @Test
    public void refreshUpdatesActiveManualAcTempGearFanAndVentFromCloudTelemetry() {
        configStore.setManualAcMode(true);
        configStore.setDefaultTempGear(1);
        configStore.setDefaultFanLevel(2);
        configStore.setDefaultWindMode(ConfigStore.WIND_MODE_BODY);

        fakeGateway.currentStatus = new VehicleStatus.Builder(60, 180, true, true, null, 17, 1700000700L)
                .manualAc(true)
                .acPreset(1, 4, ConfigStore.WIND_MODE_BODY)
                .build();

        activity.findViewById(R.id.btnRefresh).performClick();
        awaitAsyncWork();

        assertEquals("Lvl 1 · Fan 4 · Body",
                ((TextView) activity.findViewById(R.id.tvAcTogglePreset)).getText().toString());
        assertEquals(1, configStore.getDefaultTempGear());
        assertEquals(4, configStore.getDefaultFanLevel());

        activity.findViewById(R.id.btnAcAdjust).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertEquals("Lvl 1", ((TextView) dialog.findViewById(R.id.tvTargetTemp)).getText().toString());
        assertEquals("Lvl 4", ((TextView) dialog.findViewById(R.id.tvFanLevel)).getText().toString());
        dialog.dismiss();
    }

    @Test
    public void flakyInternetRefreshShowsGracefulMessageWithoutRawHostname() {
        fakeGateway.transportError = new java.net.UnknownHostException(
                "Unable to resolve host \"dilinkappoversea-id.byd.auto\": No address associated with hostname");

        activity.findViewById(R.id.btnRefresh).performClick();
        awaitAsyncWork();

        String logText = ((TextView) activity.findViewById(R.id.tvStatusLog)).getText().toString();
        String latestToast = ShadowToast.getTextOfLatestToast();
        assertTrue(logText.contains("Unable to reach BYD Cloud"));
        assertFalse(logText.contains("dilinkappoversea-id.byd.auto"));
        assertFalse(logText.contains("No address associated"));
        assertNotNull(latestToast);
        assertTrue(latestToast.contains("Unable to reach BYD Cloud"));
        assertFalse(latestToast.contains("dilinkappoversea-id.byd.auto"));
    }

    @Test
    public void accountSetupGuideStartsExpandedWhenUnconfiguredAndTogglesWhenConfigured() {
        View guideSteps = activity.findViewById(R.id.layoutSetupGuideSteps);
        Button btnToggleGuide = activity.findViewById(R.id.btnToggleSetupGuide);
        TextView tvWarning = activity.findViewById(R.id.tvSetupGuideWarning);

        assertNotNull(guideSteps);
        assertNotNull(btnToggleGuide);
        assertNotNull(tvWarning);
        assertEquals(View.GONE, guideSteps.getVisibility());
        assertEquals("Show", btnToggleGuide.getText().toString());

        btnToggleGuide.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, guideSteps.getVisibility());
        assertEquals("Hide", btnToggleGuide.getText().toString());
        assertTrue(tvWarning.getText().toString().contains("Do NOT use your main owner account"));
        assertTrue(tvWarning.getText().toString().contains("2nd BYD account"));

        // Fresh unconfigured launch expands setup guide and credential form automatically
        ActivityController<MainActivity> freshController = null;
        try {
            configStore.clear();
            freshController = Robolectric.buildActivity(MainActivity.class).setup();
            MainActivity freshActivity = freshController.get();
            ShadowLooper.idleMainLooper();

            assertEquals(View.VISIBLE, freshActivity.findViewById(R.id.cardAccount).getVisibility());
            assertEquals(View.VISIBLE, freshActivity.findViewById(R.id.layoutSetupGuideSteps).getVisibility());
            assertEquals(View.VISIBLE, freshActivity.findViewById(R.id.layoutAccountFields).getVisibility());
        } finally {
            if (freshController != null) {
                freshController.destroy();
            }
        }
    }

    @Test
    public void displaysNoticeThatHuaweiWatchIntegrationIsNotWorking() {
        TextView watchNotice = activity.findViewById(R.id.tvWatchIntegrationNotice);
        assertNotNull(watchNotice);
        String msg = watchNotice.getText().toString().toLowerCase();
        assertTrue(msg.contains("huawei watch"));
        assertTrue(msg.contains("not working"));

        try (ActivityController<SplashActivity> splashController =
                     Robolectric.buildActivity(SplashActivity.class).setup()) {
            SplashActivity splash = splashController.get();
            TextView splashWatchNotice = splash.findViewById(R.id.tvSplashWatchNotice);
            assertNotNull(splashWatchNotice);
            String splashMsg = splashWatchNotice.getText().toString().toLowerCase();
            assertTrue(splashMsg.contains("huawei watch"));
            assertTrue(splashMsg.contains("not working"));
        }
    }

    @Test
    public void widgetStyleSelectorSwitchesAllFourOptionsInSettings() {
        Button btnCapsule = activity.findViewById(R.id.btnWidgetStyleCapsule);
        Button btnRing = activity.findViewById(R.id.btnWidgetStyleRing);
        Button btnBento = activity.findViewById(R.id.btnWidgetStyleBento);
        Button btnTank = activity.findViewById(R.id.btnWidgetStyleTank);
        TextView tvDesc = activity.findViewById(R.id.tvWidgetStyleDesc);

        assertEquals(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_CAPSULE, configStore.getWidgetStyle());
        assertEquals(activity.getString(R.string.widget_style_desc_capsule), tvDesc.getText().toString());

        btnRing.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING, configStore.getWidgetStyle());
        assertEquals(activity.getString(R.string.widget_style_desc_ring), tvDesc.getText().toString());

        btnBento.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO, configStore.getWidgetStyle());
        assertEquals(activity.getString(R.string.widget_style_desc_bento), tvDesc.getText().toString());

        btnTank.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_TANK, configStore.getWidgetStyle());
        assertEquals(activity.getString(R.string.widget_style_desc_tank), tvDesc.getText().toString());

        btnCapsule.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_CAPSULE, configStore.getWidgetStyle());
        assertEquals(activity.getString(R.string.widget_style_desc_capsule), tvDesc.getText().toString());
    }

    @Test
    public void languageSelectorInSettingsSwitchesBetweenSystemEnglishAndIndonesian() {
        try {
            Button btnSystem = activity.findViewById(R.id.btnLangSystem);
            Button btnEnglish = activity.findViewById(R.id.btnLangEnglish);
            Button btnIndonesian = activity.findViewById(R.id.btnLangIndonesian);
            assertNotNull(btnSystem);
            assertNotNull(btnEnglish);
            assertNotNull(btnIndonesian);
            assertEquals(com.bydwatch.companion.data.ConfigStore.LANG_SYSTEM, configStore.getAppLanguage());

            btnIndonesian.performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(com.bydwatch.companion.data.ConfigStore.LANG_ID, configStore.getAppLanguage());
            String tagId = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags();
            assertTrue("Expected id locale tag but was: " + tagId,
                    tagId.startsWith("id") || tagId.startsWith("in"));

            android.content.Context idCtx = AppLocaleHelper.localizedContext(activity, configStore);
            assertEquals("Kunci", idCtx.getString(R.string.btn_lock));
            assertEquals("Bahasa Aplikasi", idCtx.getString(R.string.label_app_language));
            assertEquals("Preferensi", idCtx.getString(R.string.card_preferences_title));

            btnEnglish.performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(com.bydwatch.companion.data.ConfigStore.LANG_EN, configStore.getAppLanguage());
            assertEquals("en", androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags());
            android.content.Context enCtx = AppLocaleHelper.localizedContext(activity, configStore);
            assertEquals("Lock", enCtx.getString(R.string.btn_lock));

            btnSystem.performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(com.bydwatch.companion.data.ConfigStore.LANG_SYSTEM, configStore.getAppLanguage());
            assertTrue(androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().isEmpty());
        } finally {
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList());
        }
    }

    @Test
    public void liveWidgetPreviewUpdatesWithStyleCustomSlotsAndResetsCleanly() {
        android.widget.FrameLayout previewContainer = activity.findViewById(R.id.flWidgetPreviewContainer);
        assertNotNull(previewContainer);
        assertTrue(previewContainer.getChildCount() > 0);
        View previewView = previewContainer.getChildAt(0);
        assertEquals("Lock", ((Button) previewView.findViewById(R.id.btnWidgetLock)).getText().toString());
        assertEquals("Unlock", ((Button) previewView.findViewById(R.id.btnWidgetUnlock)).getText().toString());
        assertEquals("AC On", ((Button) previewView.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertEquals("Sync", ((Button) previewView.findViewById(R.id.btnWidgetRefresh)).getText().toString());

        // Switching 4x3 style to Bento swaps preview to Bento layout
        activity.findViewById(R.id.btnWidgetStyleBento).performClick();
        ShadowLooper.idleMainLooper();
        View bentoPreview = previewContainer.getChildAt(0);
        assertNotNull(bentoPreview.findViewById(R.id.tvWidgetBatteryLabel));

        // Save custom combo + slot override and verify both slot bar and live preview update
        com.bydwatch.companion.model.CustomActionCombo combo =
                new com.bydwatch.companion.model.CustomActionCombo(
                        "cmb_prev",
                        "Cool Drive",
                        com.bydwatch.companion.model.CustomActionCombo.ICON_FROST,
                        com.bydwatch.companion.model.CustomActionCombo.TONE_CYAN,
                        java.util.Collections.singletonList(
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_UNLOCK)
                        )
                );
        configStore.setActionCombos(java.util.Collections.singletonList(combo));
        configStore.setWidgetSlots(java.util.Arrays.asList(
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LIGHTS),
                com.bydwatch.companion.model.WidgetSlotConfig.combo("cmb_prev", ""),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_AC_TOGGLE),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LOCATION)
        ));
        activity.findViewById(R.id.btnWidgetStyleTank).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("1. Lights", ((Button) activity.findViewById(R.id.btnWidgetSlot1)).getText().toString());
        assertEquals("2. Cool Drive", ((Button) activity.findViewById(R.id.btnWidgetSlot2)).getText().toString());
        View tankPreview = previewContainer.getChildAt(0);
        assertEquals("Lights", ((Button) tankPreview.findViewById(R.id.btnWidgetLock)).getText().toString());
        assertEquals("Cool Drive", ((Button) tankPreview.findViewById(R.id.btnWidgetUnlock)).getText().toString());
        assertEquals("Locate", ((Button) tankPreview.findViewById(R.id.btnWidgetRefresh)).getText().toString());

        // User-facing strings must avoid internal/legal jargon
        android.view.ViewGroup combosContainer = activity.findViewById(R.id.layoutCombosContainer);
        assertNotNull(combosContainer);
        assertEquals(1, combosContainer.getChildCount());
        assertFalse(containsTextViewWithText(combosContainer, "Copyright-Free"));
        assertFalse(containsTextViewWithText(combosContainer, "WatchProtocol"));
        assertFalse(containsTextViewWithText(combosContainer, "Safe Sequential Execution"));

        // Reset restores default 4 buttons
        activity.findViewById(R.id.btnResetWidgetSlots).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("1. Lock", ((Button) activity.findViewById(R.id.btnWidgetSlot1)).getText().toString());
        assertEquals("4. Sync", ((Button) activity.findViewById(R.id.btnWidgetSlot4)).getText().toString());
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
