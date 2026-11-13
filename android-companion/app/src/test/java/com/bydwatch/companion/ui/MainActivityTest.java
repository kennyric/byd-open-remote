package com.bydwatch.companion.ui;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

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

        // Test empty password
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
}
