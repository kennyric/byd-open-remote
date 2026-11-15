package com.bydwatch.companion.ui;

import android.app.AlarmManager;
import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class VehicleWidgetProviderTest {

    private Application app;
    private BydRepositoryTest.InMemoryConfigStore configStore;
    private BydRepositoryTest.FakeBydGateway fakeGateway;
    private BydRepository repository;
    private int compactWidgetId;

    private void awaitRepo() throws Exception {
        repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
    }

    @Before
    public void setUp() {
        BydWatchApp.resetForTesting();
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("byd_widget_prefs", Context.MODE_PRIVATE).edit().clear().commit();

        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("user@byd.example");
        configStore.setPassword("secret123");
        configStore.setPin("123456");
        configStore.setVin("LGX_WIDGET_VIN");
        configStore.setVehicleName("Atto 1");
        configStore.setDistanceUnit(UnitPreferences.DIST_KM);

        fakeGateway = new BydRepositoryTest.FakeBydGateway();
        fakeGateway.currentStatus = new VehicleStatus.Builder(84, 340, true, false, 26, 20, 1700000000L)
                .modelName("Atto 1")
                .build();

        repository = new BydRepository(fakeGateway, configStore);
        BydWatchApp.setTestDependencies(configStore, fakeGateway, repository);

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        compactWidgetId = shadowMgr.createWidget(VehicleWidgetProvider.class, R.layout.widget_vehicle_2x2);
        // Drain any background status sync started by ShadowAppWidgetManager.createWidget()
        try {
            awaitRepo();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        configStore.setWidgetLastSyncMs(0L);
        fakeGateway.fetchRealtimeCalls.set(0);
    }

    @After
    public void tearDown() throws Exception {
        VehicleLocationHelper.awaitExecutorForTesting();
        VehicleLocationHelper.clearCacheForTesting();
        WidgetSyncJobService.cancelPeriodicSync(app);
        BydWatchApp.resetForTesting();
    }

    @Test
    public void compact2x2WidgetShowsBatteryRangeBigButtonsAndSchedules30MinJob() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        Bundle compactOpts = new Bundle();
        compactOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160);
        mgr.updateAppWidgetOptions(compactWidgetId, compactOpts);

        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onEnabled(app);
        provider.onUpdate(app, mgr, new int[]{compactWidgetId});
        awaitRepo();

        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo job = scheduler.getPendingJob(WidgetSyncJobService.JOB_ID_PERIODIC_SYNC);
        assertNotNull("Expected 30-minute periodic JobInfo to be scheduled", job);
        assertEquals(30L * 60L * 1000L, job.getIntervalMillis());
        assertEquals(1, fakeGateway.fetchRealtimeCalls.get());

        // Simulate cold-start loss of in-memory cache and confirm ConfigStore fallback restores redacted status
        // without leaving plaintext status JSON in unencrypted widget preferences.
        assertFalse(app.getSharedPreferences("byd_widget_prefs", Context.MODE_PRIVATE)
                .contains("last_status_json"));
        repository.setCachedStatus(null);
        VehicleStatus restored = VehicleWidgetProvider.resolveStatus(app);
        assertNotNull(restored);
        assertEquals(84, restored.soc);
        assertEquals("", restored.vin);

        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        View view = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("84%", ((TextView) view.findViewById(R.id.tvWidgetSoc)).getText().toString());
        assertEquals("340 km", ((TextView) view.findViewById(R.id.tvWidgetRange)).getText().toString());
        assertNotNull(view.findViewById(R.id.btnWidgetLock));
        assertNotNull(view.findViewById(R.id.btnWidgetUnlock));
        assertNotNull(view.findViewById(R.id.btnWidgetAc));
        assertEquals("AC On", ((Button) view.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertNotNull(view.findViewById(R.id.btnWidgetRefresh));
        assertNull("2x2 widget should stay minimal without AC status or sync pill",
                view.findViewById(R.id.tvWidgetAcStatus));
        assertNull("2x2 widget should remain unchanged without location bar",
                view.findViewById(R.id.layoutWidgetLocationBar));

        // 2x2 AC button toggles AC On -> updates text to AC Off -> toggles AC Off
        WidgetActionReceiver actionReceiver = new WidgetActionReceiver();
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AC));
        awaitRepo();
        assertEquals(1, fakeGateway.startClimateCalls.get());
        View acOn2x2View = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("AC Off", ((Button) acOn2x2View.findViewById(R.id.btnWidgetAc)).getText().toString());

        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AC));
        awaitRepo();
        assertEquals(1, fakeGateway.stopClimateCalls.get());
        View acOff2x2View = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("AC On", ((Button) acOff2x2View.findViewById(R.id.btnWidgetAc)).getText().toString());

        provider.onDisabled(app);
        assertNull(scheduler.getPendingJob(WidgetSyncJobService.JOB_ID_PERIODIC_SYNC));
    }

    @Test
    public void wide4x2WidgetShowsSyncTimeAcStatusMergedAcToggleAndExecutesActionsWithoutApp() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        Bundle wideOpts = new Bundle();
        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);

        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);
        provider.onUpdate(app, mgr, new int[]{compactWidgetId});
        awaitRepo();

        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        View wideView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("84%", ((TextView) wideView.findViewById(R.id.tvWidgetSoc)).getText().toString());
        assertEquals("340 km", ((TextView) wideView.findViewById(R.id.tvWidgetRange)).getText().toString());
        assertEquals("Locked", ((TextView) wideView.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals("AC Off", ((TextView) wideView.findViewById(R.id.tvWidgetAcStatus)).getText().toString());
        assertEquals("AC On", ((Button) wideView.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertEquals(View.GONE, wideView.findViewById(R.id.tvWidgetBanner).getVisibility());
        assertEquals("Closed", ((TextView) wideView.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());
        String syncLabel = ((TextView) wideView.findViewById(R.id.tvWidgetLastSync)).getText().toString();
        assertTrue(syncLabel.startsWith("Last sync "));
        assertFalse(syncLabel.endsWith("--:--"));

        WidgetActionReceiver actionReceiver = new WidgetActionReceiver();

        // Untrusted broadcast directly to exported VehicleWidgetProvider must be ignored
        provider.onReceive(app, new Intent(app, VehicleWidgetProvider.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_UNLOCK));
        awaitRepo();
        assertEquals(0, fakeGateway.unlockCalls.get());

        // Unlock from internal WidgetActionReceiver without opening MainActivity
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_UNLOCK));
        awaitRepo();
        assertEquals(1, fakeGateway.unlockCalls.get());
        assertFalse(repository.getCachedStatus().locked);
        assertNull("Unlock widget action must not launch MainActivity", Shadows.shadowOf(app).getNextStartedActivity());

        // Lock from widget without opening MainActivity
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_LOCK));
        awaitRepo();
        assertEquals(1, fakeGateway.lockCalls.get());
        assertTrue(repository.getCachedStatus().locked);
        assertNull("Lock widget action must not launch MainActivity", Shadows.shadowOf(app).getNextStartedActivity());

        // Merged AC button toggles On -> updates AC status & button text -> toggles Off
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AC));
        awaitRepo();
        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertTrue(repository.getCachedStatus().acOn);

        View wideAcOnView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("AC On", ((TextView) wideAcOnView.findViewById(R.id.tvWidgetAcStatus)).getText().toString());
        assertEquals("AC Off", ((Button) wideAcOnView.findViewById(R.id.btnWidgetAc)).getText().toString());

        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AC));
        awaitRepo();
        assertEquals(1, fakeGateway.stopClimateCalls.get());
        assertFalse(repository.getCachedStatus().acOn);
        assertNull("AC widget action must not launch MainActivity", Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void changingIntervalSupportsHoursViaJobSchedulerAndShortMinutesViaAlarmManager() throws Exception {
        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onEnabled(app);
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager shadowAlarm = Shadows.shadowOf(am);

        // 2 hours (120 min) -> reschedules JobScheduler to 120 minutes, no short alarm
        configStore.setWidgetSyncIntervalMinutes(120);
        WidgetSyncJobService.reschedulePeriodicSync(app);
        JobInfo twoHourJob = scheduler.getPendingJob(WidgetSyncJobService.JOB_ID_PERIODIC_SYNC);
        assertNotNull(twoHourJob);
        assertEquals(120L * 60L * 1000L, twoHourJob.getIntervalMillis());
        assertTrue(shadowAlarm.getScheduledAlarms().isEmpty());

        // 10 minutes (< 15 min OS JobScheduler floor) -> cancels JobScheduler job and uses AlarmManager
        configStore.setWidgetSyncIntervalMinutes(10);
        WidgetSyncJobService.reschedulePeriodicSync(app);
        assertNull(scheduler.getPendingJob(WidgetSyncJobService.JOB_ID_PERIODIC_SYNC));
        assertFalse("Short interval (<15m) should schedule AlarmManager wakeup", shadowAlarm.getScheduledAlarms().isEmpty());

        // Firing ACTION_WIDGET_AUTO_SYNC executes telemetry fetch and re-arms next short alarm
        int beforeCalls = fakeGateway.fetchRealtimeCalls.get();
        new WidgetActionReceiver().onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AUTO_SYNC));
        awaitRepo();
        assertEquals(beforeCalls + 1, fakeGateway.fetchRealtimeCalls.get());
        assertFalse(shadowAlarm.getScheduledAlarms().isEmpty());

        // Switching back to 45 minutes cancels AlarmManager wakeup and restores JobScheduler
        configStore.setWidgetSyncIntervalMinutes(45);
        WidgetSyncJobService.reschedulePeriodicSync(app);
        JobInfo fortyFiveMinJob = scheduler.getPendingJob(WidgetSyncJobService.JOB_ID_PERIODIC_SYNC);
        assertNotNull(fortyFiveMinJob);
        assertEquals(45L * 60L * 1000L, fortyFiveMinJob.getIntervalMillis());
        assertTrue(shadowAlarm.getScheduledAlarms().isEmpty());
    }

    @Test
    public void widgetsReflectChargingStatusIn2x2And4x2Modes() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        fakeGateway.currentStatus = new VehicleStatus.Builder(72, 290, true, false, 25, 20, 1700000400L)
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargeRemainingMinutes(45)
                .chargePowerKw(7.2)
                .build();

        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onUpdate(app, mgr, new int[]{compactWidgetId});
        awaitRepo();

        View compactView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("290 km\n⚡ 7.2 kW · 0h 45m",
                ((TextView) compactView.findViewById(R.id.tvWidgetRange)).getText().toString());

        Bundle wideOpts = new Bundle();
        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);

        View wideView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(View.VISIBLE, wideView.findViewById(R.id.tvWidgetBanner).getVisibility());
        assertEquals("⚡ 7.2 kW · 0h 45m",
                ((TextView) wideView.findViewById(R.id.tvWidgetBanner)).getText().toString());
        assertEquals("Locked",
                ((TextView) wideView.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals(72, ((android.widget.ProgressBar) wideView.findViewById(R.id.pbWidgetSoc)).getProgress());
        assertEquals(View.GONE, wideView.findViewById(R.id.layoutWidgetLocationBar).getVisibility());

        // Resizing to 4x1 compact single row keeps battery progress bar, range, status, and all 4 buttons
        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 64);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);

        View shortWideView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("72%", ((TextView) shortWideView.findViewById(R.id.tvWidgetSoc)).getText().toString());
        assertEquals("290 km", ((TextView) shortWideView.findViewById(R.id.tvWidgetRange)).getText().toString());
        assertEquals(72, ((android.widget.ProgressBar) shortWideView.findViewById(R.id.pbWidgetSoc)).getProgress());
        assertEquals("⚡ 7.2 kW · 0h 45m · Locked · AC Off",
                ((TextView) shortWideView.findViewById(R.id.tvWidgetBanner)).getText().toString());
        assertNull(shortWideView.findViewById(R.id.layoutWidgetLocationBar));

        // Resizing EV Watch Status widget to 3+ rows reveals location bar instead of stretching buttons
        VehicleLocationHelper.setTestStreetLookupForTesting((ctx, lat, lng) -> "Jl. Antasari, Kedamaian");
        fakeGateway.currentStatus = fakeGateway.currentStatus.toBuilder()
                .location(-5.40639, 105.30289, 1700000450L)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);

        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);
        VehicleLocationHelper.awaitExecutorForTesting();
        ShadowLooper.idleMainLooper();

        View tallWideView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(72, ((android.widget.ProgressBar) tallWideView.findViewById(R.id.pbWidgetSoc)).getProgress());
        assertEquals(View.VISIBLE, tallWideView.findViewById(R.id.layoutWidgetLocationBar).getVisibility());
        assertEquals("Jl. Antasari, Kedamaian",
                ((TextView) tallWideView.findViewById(R.id.tvWidgetLocationText)).getText().toString());
        // API modelName ("BYD SEAGULL/BYD ATTO 1") and temps are hidden unless preferred custom model is configured
        assertEquals(View.GONE, tallWideView.findViewById(R.id.tvWidgetCarModel).getVisibility());
        assertEquals("Closed", ((TextView) tallWideView.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());

        // Populate preferred custom model, outside temp (must remain hidden), and odometer
        configStore.setCustomVehicleName("BYD Atto 1");
        fakeGateway.currentStatus = fakeGateway.currentStatus.toBuilder()
                .modelName("BYD SEAGULL/BYD ATTO 1")
                .outsideTempC(33)
                .totalMileageKm(24028)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);
        View fullTallView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(View.VISIBLE, fullTallView.findViewById(R.id.tvWidgetCarModel).getVisibility());
        assertEquals("BYD Atto 1",
                ((TextView) fullTallView.findViewById(R.id.tvWidgetCarModel)).getText().toString());
        assertEquals("24,028 km · Closed",
                ((TextView) fullTallView.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());

        // Unavailable status & cleared custom name hides optional pills cleanly
        repository.setCachedStatus(null);
        configStore.setCustomVehicleName("");
        configStore.setVehicleName("");
        configStore.setWidgetStatusJson("");
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);
        View emptyTallView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(View.GONE, emptyTallView.findViewById(R.id.tvWidgetCarModel).getVisibility());
        assertEquals(View.GONE, emptyTallView.findViewById(R.id.tvWidgetLockStatus).getVisibility());
        assertEquals(View.GONE, emptyTallView.findViewById(R.id.tvWidgetAcStatus).getVisibility());
        assertEquals(View.GONE, emptyTallView.findViewById(R.id.tvWidgetBatteryMeta).getVisibility());
    }

    @Test
    public void widgetsShowDrivingStatusAcrossAllLayoutsWhenVehicleOn() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        fakeGateway.currentStatus = new VehicleStatus.Builder(79, 237, false, false, 26, 20, 1700000500L)
                .chargingState(VehicleStatus.CHARGING_NONE)
                .vehicleOn(true)
                .modelName("BYD SEAGULL/BYD ATTO 1")
                .outsideTempC(33)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);
        configStore.setCustomVehicleName("BYD Atto 1");

        VehicleWidgetProvider provider = new VehicleWidgetProvider();

        // 2x2
        Bundle opts = new Bundle();
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160);
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120);
        mgr.updateAppWidgetOptions(compactWidgetId, opts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, opts);
        assertEquals("237 km\nDriving / On",
                ((TextView) shadowMgr.getViewFor(compactWidgetId).findViewById(R.id.tvWidgetRange)).getText().toString());

        // 4x1
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 64);
        mgr.updateAppWidgetOptions(compactWidgetId, opts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, opts);
        assertEquals("Driving / On · Unlocked · AC Off",
                ((TextView) shadowMgr.getViewFor(compactWidgetId).findViewById(R.id.tvWidgetBanner)).getText().toString());

        // 4x2 (4x3 Twin): Driving/Lock/AC render as top-right pills & car model on top-left
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120);
        mgr.updateAppWidgetOptions(compactWidgetId, opts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, opts);
        View midView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("Driving / On",
                ((TextView) midView.findViewById(R.id.tvWidgetBanner)).getText().toString());
        assertEquals("Unlocked",
                ((TextView) midView.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals("BYD Atto 1",
                ((TextView) midView.findViewById(R.id.tvWidgetCarModel)).getText().toString());

        // 4x3: Driving/Lock/AC render as individual pills, and parked hides Driving pill while keeping Lock/AC pills
        opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190);
        mgr.updateAppWidgetOptions(compactWidgetId, opts);
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, opts);
        View tallView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(View.VISIBLE, tallView.findViewById(R.id.tvWidgetBanner).getVisibility());
        assertEquals("Driving / On",
                ((TextView) tallView.findViewById(R.id.tvWidgetBanner)).getText().toString());
        assertEquals(View.VISIBLE, tallView.findViewById(R.id.tvWidgetLockStatus).getVisibility());
        assertEquals("Unlocked",
                ((TextView) tallView.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals(View.VISIBLE, tallView.findViewById(R.id.tvWidgetAcStatus).getVisibility());
        assertEquals("AC Off",
                ((TextView) tallView.findViewById(R.id.tvWidgetAcStatus)).getText().toString());
        assertEquals("BYD Atto 1",
                ((TextView) tallView.findViewById(R.id.tvWidgetCarModel)).getText().toString());
        assertEquals(View.VISIBLE, tallView.findViewById(R.id.tvWidgetLastSync).getVisibility());
        assertTrue(((TextView) tallView.findViewById(R.id.tvWidgetLastSync)).getText().toString().startsWith("Last sync "));

        // Parked idle 4x3 hides tvWidgetBanner pill and keeps Lock + AC pills side-by-side
        fakeGateway.currentStatus = fakeGateway.currentStatus.toBuilder()
                .vehicleOn(false)
                .build();
        repository.setCachedStatus(new VehicleStatus.Builder(79, 237, true, false, 26, 20, 1700000510L)
                .chargingState(VehicleStatus.CHARGING_NONE)
                .vehicleOn(false)
                .build());
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, opts);
        View parkedTallView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals(View.GONE, parkedTallView.findViewById(R.id.tvWidgetBanner).getVisibility());
        assertEquals(View.VISIBLE, parkedTallView.findViewById(R.id.tvWidgetLockStatus).getVisibility());
        assertEquals("Locked",
                ((TextView) parkedTallView.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals(View.VISIBLE, parkedTallView.findViewById(R.id.tvWidgetAcStatus).getVisibility());
    }

    @Test
    public void widgetAcOnUsesSavedFanAndVentSettingsInManualAcMode() throws Exception {
        configStore.setManualAcMode(true);
        configStore.setDefaultTempGear(5);
        configStore.setDefaultFanLevel(6);
        configStore.setDefaultWindMode(com.bydwatch.companion.data.ConfigStore.WIND_MODE_BODY_FEET);
        repository.setCachedStatus(new VehicleStatus.Builder(79, 237, true, false, 26, 20, 1700000510L)
                .manualAc(true)
                .build());

        new WidgetActionReceiver().onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_AC));
        awaitRepo();

        assertEquals(1, fakeGateway.startElectricClimateCalls.get());
        assertEquals(5, fakeGateway.lastTempGear);
        assertEquals(6, fakeGateway.lastWindLevel);
        assertEquals(com.bydwatch.companion.data.ConfigStore.WIND_MODE_BODY_FEET, fakeGateway.lastWindMode);
    }

    @Test
    public void widgetFailureBannerMapsNetworkAndApiErrorsConcisely() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        Bundle wideOpts = new Bundle();
        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);
        new VehicleWidgetProvider().onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wideOpts);

        fakeGateway.transportError = new java.net.UnknownHostException(
                "Unable to resolve host \"dilinkappoversea-id.byd.auto\": No address associated with hostname");

        new WidgetActionReceiver().onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_REFRESH));
        awaitRepo();

        View view = Shadows.shadowOf(mgr).getViewFor(compactWidgetId);
        TextView banner = view.findViewById(R.id.tvWidgetBanner);
        assertEquals(View.VISIBLE, banner.getVisibility());
        assertEquals("No internet", banner.getText().toString());
    }

    @Test
    public void widgetLastSyncTimestampOnlyUpdatesAfterSuccessfulSync() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        Bundle wideOpts = new Bundle();
        wideOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        mgr.updateAppWidgetOptions(compactWidgetId, wideOpts);

        long oldSyncMs = 1_700_000_000_000L;
        repository.setCachedStatus(fakeGateway.currentStatus);
        configStore.setWidgetLastSyncMs(oldSyncMs);
        fakeGateway.operationDelayMs = 80;
        fakeGateway.transportError = new java.net.UnknownHostException("offline");

        WidgetActionReceiver receiver = new WidgetActionReceiver();
        receiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_REFRESH));
        // Immediately after click ("Syncing..."), previous last-sync timestamp must not change
        assertEquals(oldSyncMs, configStore.getWidgetLastSyncMs());

        // After failed sync finishes, previous last-sync timestamp must still remain unchanged
        awaitRepo();
        assertEquals(oldSyncMs, configStore.getWidgetLastSyncMs());

        // Only after a successful sync completes does widgetLastSyncMs advance
        fakeGateway.operationDelayMs = 0;
        fakeGateway.transportError = null;
        receiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.ACTION_WIDGET_REFRESH));
        awaitRepo();
        assertTrue(configStore.getWidgetLastSyncMs() > oldSyncMs);
    }

    @Test
    public void tall4x3WidgetSupportsAllFourBatteryStylesAndSingleRowLocationBar() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        Bundle tallOpts = new Bundle();
        tallOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        tallOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190);
        mgr.updateAppWidgetOptions(compactWidgetId, tallOpts);

        VehicleLocationHelper.setTestStreetLookupForTesting((ctx, lat, lng) -> "Jalan W. R. Supratman");
        fakeGateway.currentStatus = new VehicleStatus.Builder(46, 138, true, false, 26, 20, 1700000600L)
                .totalMileageKm(24111)
                .location(-5.40639, 105.30289, 1700000600L)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);

        int[] styles = {
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_CAPSULE,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_TANK
        };
        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        for (int style : styles) {
            configStore.setWidgetStyle(style);
            provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, tallOpts);
            VehicleLocationHelper.awaitExecutorForTesting();
            ShadowLooper.idleMainLooper();

            View v = shadowMgr.getViewFor(compactWidgetId);
            assertEquals("46%", ((TextView) v.findViewById(R.id.tvWidgetSoc)).getText().toString());
            assertEquals("138 km", ((TextView) v.findViewById(R.id.tvWidgetRange)).getText().toString());
            assertEquals(46, ((android.widget.ProgressBar) v.findViewById(R.id.pbWidgetSoc)).getProgress());
            assertEquals("24,111 km · Closed",
                    ((TextView) v.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());
            assertEquals("Jalan W. R. Supratman",
                    ((TextView) v.findViewById(R.id.tvWidgetLocationText)).getText().toString());
            assertNotNull(v.findViewById(R.id.layoutWidgetLocationBar));
            assertEquals(1, ((android.view.ViewGroup) v.findViewById(R.id.layoutWidgetLocationBar)).getChildCount() >= 3
                    ? 1 : 0);
        }
    }

    @Test
    public void widgetLocalizesButtonsAndStatusToIndonesianWhenConfigured() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        Bundle tallOpts = new Bundle();
        tallOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        tallOpts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190);
        mgr.updateAppWidgetOptions(compactWidgetId, tallOpts);

        configStore.setAppLanguage(com.bydwatch.companion.data.ConfigStore.LANG_ID);
        configStore.setWidgetStyle(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO);
        fakeGateway.currentStatus = new VehicleStatus.Builder(82, 310, true, false, 24, 19, 1700000700L)
                .totalMileageKm(12500)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);

        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, tallOpts);

        View v = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("Kunci", ((TextView) v.findViewById(R.id.btnWidgetLock)).getText().toString());
        assertEquals("Buka Kunci", ((TextView) v.findViewById(R.id.btnWidgetUnlock)).getText().toString());
        assertEquals("AC Nyala", ((TextView) v.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertEquals("Sinkron", ((TextView) v.findViewById(R.id.btnWidgetRefresh)).getText().toString());
        assertEquals("Terkunci", ((TextView) v.findViewById(R.id.tvWidgetLockStatus)).getText().toString());
        assertEquals("Baterai", ((TextView) v.findViewById(R.id.tvWidgetBatteryLabel)).getText().toString());
        assertEquals("12,500 km · Tertutup",
                ((TextView) v.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());
    }

    @Test
    public void customSlotsUpdateAllLayoutsAndExecuteSequentialComboAndSingleActions() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        Bundle wide4x2 = new Bundle();
        wide4x2.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        wide4x2.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);

        com.bydwatch.companion.model.CustomActionCombo combo =
                new com.bydwatch.companion.model.CustomActionCombo(
                        "cmb_morning",
                        "Morning",
                        com.bydwatch.companion.model.CustomActionCombo.ICON_SUN,
                        com.bydwatch.companion.model.CustomActionCombo.TONE_AMBER,
                        java.util.Arrays.asList(
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_UNLOCK),
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_AC_ON,
                                        true, false, 19, 3, 4, 1, -1),
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_LIGHTS)
                        )
                );
        configStore.setActionCombos(java.util.Collections.singletonList(combo));
        configStore.setWidgetSlots(java.util.Arrays.asList(
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LIGHTS),
                com.bydwatch.companion.model.WidgetSlotConfig.combo("cmb_morning", "Warmup"),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_VENT_WINDOWS),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LOCATION)
        ));

        VehicleWidgetProvider provider = new VehicleWidgetProvider();
        provider.onAppWidgetOptionsChanged(app, mgr, compactWidgetId, wide4x2);
        View wideView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("Lights", ((Button) wideView.findViewById(R.id.btnWidgetLock)).getText().toString());
        assertEquals("Warmup", ((Button) wideView.findViewById(R.id.btnWidgetUnlock)).getText().toString());
        assertEquals("Vent Win", ((Button) wideView.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertEquals("Locate", ((Button) wideView.findViewById(R.id.btnWidgetRefresh)).getText().toString());
        assertNotNull(wideView.findViewById(R.id.ivWidgetUnlock));

        // Slot 0 single action -> flashLights
        WidgetActionReceiver receiver = new WidgetActionReceiver();
        receiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(VehicleWidgetProvider.slotAction(0)));
        awaitRepo();
        assertEquals(1, fakeGateway.flashLightsCalls.get());

        // Slot 1 multi-step combo -> unlock + startClimate(19C) + flashLights
        VehicleWidgetProvider.comboStepDelayMs = 0L;
        try {
            receiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                    .setAction(VehicleWidgetProvider.slotAction(1)));
            VehicleWidgetProvider.awaitComboExecutorForTesting();
            awaitRepo();
        } finally {
            VehicleWidgetProvider.comboStepDelayMs = 800L;
        }
        assertEquals(1, fakeGateway.unlockCalls.get());
        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals(19.0, fakeGateway.lastTempCelsius, 0.01);
        assertEquals(2, fakeGateway.flashLightsCalls.get());
        assertNull(VehicleWidgetProvider.getActiveBanner(app));
    }

    @Test
    public void wide4x2WidgetSupportsAllFourBatteryStylesAndTracksSettingChangesWith4x3() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        int secondWidgetId = shadowMgr.createWidget(VehicleWidgetProvider.class, R.layout.widget_vehicle_4x3);
        awaitRepo();

        Bundle wide4x2Opts = new Bundle();
        wide4x2Opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        wide4x2Opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120);
        mgr.updateAppWidgetOptions(compactWidgetId, wide4x2Opts);

        Bundle tall4x3Opts = new Bundle();
        tall4x3Opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280);
        tall4x3Opts.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 190);
        mgr.updateAppWidgetOptions(secondWidgetId, tall4x3Opts);

        fakeGateway.currentStatus = new VehicleStatus.Builder(63, 215, true, false, 26, 20, 1700000800L)
                .totalMileageKm(18420)
                .build();
        repository.setCachedStatus(fakeGateway.currentStatus);

        int[] styles = {
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_CAPSULE,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO,
                com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_TANK
        };
        for (int style : styles) {
            configStore.setWidgetStyle(style);
            VehicleWidgetProvider.refreshWidgets(app);
            ShadowLooper.idleMainLooper();

            View v4x2 = shadowMgr.getViewFor(compactWidgetId);
            assertEquals("63%", ((TextView) v4x2.findViewById(R.id.tvWidgetSoc)).getText().toString());
            assertEquals("215 km", ((TextView) v4x2.findViewById(R.id.tvWidgetRange)).getText().toString());
            assertEquals(63, ((android.widget.ProgressBar) v4x2.findViewById(R.id.pbWidgetSoc)).getProgress());
            assertEquals("18,420 km · Closed",
                    ((TextView) v4x2.findViewById(R.id.tvWidgetBatteryMeta)).getText().toString());
            assertEquals(View.GONE, v4x2.findViewById(R.id.layoutWidgetLocationBar).getVisibility());

            if (style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING) {
                assertNotNull(v4x2.findViewById(R.id.tvWidgetEstRangeLabel));
            } else if (style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO
                    || style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_TANK) {
                assertNotNull(v4x2.findViewById(R.id.tvWidgetBatteryLabel));
                assertNotNull(v4x2.findViewById(R.id.tvWidgetRangeLabel));
            } else {
                assertNull(v4x2.findViewById(R.id.tvWidgetBatteryLabel));
                assertNull(v4x2.findViewById(R.id.tvWidgetEstRangeLabel));
            }

            View v4x3 = shadowMgr.getViewFor(secondWidgetId);
            assertEquals(View.VISIBLE, v4x3.findViewById(R.id.layoutWidgetLocationBar).getVisibility());
            if (style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING) {
                assertNotNull(v4x3.findViewById(R.id.tvWidgetEstRangeLabel));
            } else if (style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO
                    || style == com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_TANK) {
                assertNotNull(v4x3.findViewById(R.id.tvWidgetBatteryLabel));
            }
        }

        // Indonesian localization applies cleanly to 4x2 Ring & Bento labels too
        configStore.setAppLanguage(com.bydwatch.companion.data.ConfigStore.LANG_ID);
        configStore.setWidgetStyle(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_RING);
        VehicleWidgetProvider.refreshWidgets(app);
        ShadowLooper.idleMainLooper();
        View ringIdView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("Estimasi Jarak",
                ((TextView) ringIdView.findViewById(R.id.tvWidgetEstRangeLabel)).getText().toString());

        configStore.setWidgetStyle(com.bydwatch.companion.data.ConfigStore.WIDGET_STYLE_BENTO);
        VehicleWidgetProvider.refreshWidgets(app);
        ShadowLooper.idleMainLooper();
        View bentoIdView = shadowMgr.getViewFor(compactWidgetId);
        assertEquals("Baterai",
                ((TextView) bentoIdView.findViewById(R.id.tvWidgetBatteryLabel)).getText().toString());
        assertEquals("Jarak Tempuh",
                ((TextView) bentoIdView.findViewById(R.id.tvWidgetRangeLabel)).getText().toString());
    }
}
