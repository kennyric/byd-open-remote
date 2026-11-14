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
    }

    @After
    public void tearDown() {
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
        assertNotNull(view.findViewById(R.id.btnWidgetRefresh));
        assertNull("2x2 widget should stay minimal without AC status or sync pill",
                view.findViewById(R.id.tvWidgetAcStatus));

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
        assertEquals("AC Off", ((TextView) wideView.findViewById(R.id.tvWidgetAcStatus)).getText().toString());
        assertEquals("AC On", ((Button) wideView.findViewById(R.id.btnWidgetAc)).getText().toString());
        assertEquals(View.VISIBLE, wideView.findViewById(R.id.tvWidgetBanner).getVisibility());
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
        assertEquals("⚡ 7.2 kW · 0h 45m · Locked",
                ((TextView) wideView.findViewById(R.id.tvWidgetBanner)).getText().toString());
        assertEquals(View.GONE, wideView.findViewById(R.id.layoutWidgetLocationBar).getVisibility());

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
        assertEquals(View.VISIBLE, tallWideView.findViewById(R.id.layoutWidgetLocationBar).getVisibility());
        assertEquals("Jl. Antasari, Kedamaian",
                ((TextView) tallWideView.findViewById(R.id.tvWidgetLocationText)).getText().toString());
    }
}
