package com.bydwatch.companion.ui;

import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.VehicleStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
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
            return returnedVin;
        }
    }

    private BydRepositoryTest.InMemoryConfigStore configStore;
    private TestGateway fakeGateway;
    private BydRepository repository;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

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

        // Direct executor so repository handleWatchJson executes synchronously on current thread
        ExecutorService directExecutor = new AbstractExecutorService() {
            private boolean shutdown = false;
            @Override public void shutdown() { shutdown = true; }
            @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };

        repository = new BydRepository(fakeGateway, configStore, directExecutor);
        BydWatchApp.setTestDependencies(configStore, fakeGateway, repository);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
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
    public void savesCredentialsToConfigStore() {
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
    public void fetchVinAndTestLoginUpdatesVinAndRefreshesStatus() {
        Button btnFetchVin = activity.findViewById(R.id.btnFetchVin);
        EditText etVin = activity.findViewById(R.id.etVin);
        TextView tvSoc = activity.findViewById(R.id.tvSoc);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnFetchVin.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(fakeGateway.loginCalls.get() >= 1);
        assertTrue(fakeGateway.fetchVinCalls.get() >= 1);
        assertEquals("BYDATTO1TESTVIN99", etVin.getText().toString());
        assertEquals("BYDATTO1TESTVIN99", configStore.getVin());
        assertTrue(tvSoc.getText().toString().contains("80"));
        assertTrue(tvLog.getText().toString().contains("VIN") || tvLog.getText().toString().contains("Login"));
    }

    @Test
    public void lockButtonSendsLockCommandAndUpdatesUI() {
        Button btnLock = activity.findViewById(R.id.btnLock);
        TextView tvLockState = activity.findViewById(R.id.tvLockState);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnLock.performClick();
        ShadowLooper.idleMainLooper();

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
        ShadowLooper.idleMainLooper();

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
        ShadowLooper.idleMainLooper();

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
        ShadowLooper.idleMainLooper();

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
        ShadowLooper.idleMainLooper();

        assertEquals("95%", tvSoc.getText().toString());
        assertEquals("380 km", tvRange.getText().toString());
    }

    @Test
    public void flashLightsAndHornTriggerCommands() {
        Button btnLights = activity.findViewById(R.id.btnLights);
        Button btnHorn = activity.findViewById(R.id.btnHorn);
        TextView tvLog = activity.findViewById(R.id.tvStatusLog);

        btnLights.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, fakeGateway.flashLightsCalls.get());

        btnHorn.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, fakeGateway.findCarCalls.get());

        assertTrue(tvLog.getText().toString().contains("Lights") || tvLog.getText().toString().contains("flashed"));
        assertTrue(tvLog.getText().toString().contains("Horn") || tvLog.getText().toString().contains("sounded"));
    }
}
