package com.bydwatch.companion.ui;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.overdrive.app.byd.cloud.BydCloudClient;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class LocationFeatureTest {

    private Application app;
    private BydRepositoryTest.InMemoryConfigStore configStore;
    private BydRepositoryTest.FakeBydGateway fakeGateway;
    private BydRepository repository;
    private int locationWidgetId;

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
        configStore.setUsername("driver@byd.example");
        configStore.setPassword("secret123");
        configStore.setPin("123456");
        configStore.setVin("LGX_LOC_VIN");
        configStore.setVehicleName("Atto 1");

        fakeGateway = new BydRepositoryTest.FakeBydGateway();
        fakeGateway.currentStatus = new VehicleStatus.Builder(82, 330, true, false, 24, 18, 1700000000L)
                .vin("LGX_LOC_VIN")
                .modelName("Atto 1")
                .build();

        repository = new BydRepository(fakeGateway, configStore);
        BydWatchApp.setTestDependencies(configStore, fakeGateway, repository);

        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        locationWidgetId = shadowMgr.createWidget(LocationWidgetProvider.class, R.layout.widget_location);
    }

    @After
    public void tearDown() {
        WidgetSyncJobService.cancelPeriodicSync(app);
        BydWatchApp.resetForTesting();
    }

    @Test
    public void extractsGpsCoordinatesFromMultipleBydPayloadFormats() throws Exception {
        JSONObject nestedData = new JSONObject()
                .put("data", new JSONObject()
                        .put("latitude", -6.2088)
                        .put("longitude", 106.8456)
                        .put("gpsTimeStamp", 1700000000000L));
        double[] coords = BydCloudClient.extractGpsCoordinates(nestedData);
        assertNotNull(coords);
        assertEquals(-6.2088, coords[0], 1e-5);
        assertEquals(106.8456, coords[1], 1e-5);
        assertEquals(1700000000.0, coords[2], 1.0);

        JSONObject xyStrings = new JSONObject()
                .put("gpsInfo", new JSONObject()
                        .put("Y", "-6.17539")
                        .put("X", "106.82715"));
        double[] xy = BydCloudClient.extractGpsCoordinates(xyStrings);
        assertNotNull(xy);
        assertEquals(-6.17539, xy[0], 1e-5);
        assertEquals(106.82715, xy[1], 1e-5);

        JSONObject invalidZero = new JSONObject().put("latitude", 0.0).put("longitude", 0.0);
        assertNull(BydCloudClient.extractGpsCoordinates(invalidZero));
    }

    @Test
    public void vehicleStatusSerializesLocationAndRepositoryPreservesKnownLocationAcrossRefresh() throws Exception {
        VehicleStatus withLoc = fakeGateway.currentStatus.toBuilder()
                .location(-6.2088, 106.8456, 1700000200L)
                .build();
        VehicleStatus decoded = VehicleStatus.fromJson(withLoc.toJson());
        assertNotNull(decoded);
        assertTrue(decoded.hasLocation());
        assertEquals(-6.2088, decoded.latitude, 1e-5);
        assertEquals(106.8456, decoded.longitude, 1e-5);
        assertEquals(Long.valueOf(1700000200L), decoded.gpsTs);

        fakeGateway.currentLocation = new BydGateway.VehicleLocation(-6.2088, 106.8456, 1700000200L);
        String locResp = repository.processWatchJson("{\"id\":\"loc1\",\"action\":\"" + WatchProtocol.ACTION_LOCATION + "\"}");
        assertTrue(locResp.contains("\"ok\":true"));
        assertTrue(repository.getCachedStatus().hasLocation());

        // Subsequent status refresh without inline GPS still preserves last known vehicle coordinates
        String statusResp = repository.processWatchJson("{\"id\":\"st1\",\"action\":\"" + WatchProtocol.ACTION_STATUS + "\"}");
        assertTrue(statusResp.contains("\"ok\":true"));
        assertTrue(repository.getCachedStatus().hasLocation());
        assertEquals(-6.2088, repository.getCachedStatus().latitude, 1e-5);
    }

    @Test
    public void compactLocationWidgetRefreshesGpsAndPromptsNavigationChooserOnTap() throws Exception {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ShadowAppWidgetManager shadowMgr = Shadows.shadowOf(mgr);
        LocationWidgetProvider provider = new LocationWidgetProvider();
        provider.onEnabled(app);
        provider.onUpdate(app, mgr, new int[]{locationWidgetId});

        View initialView = shadowMgr.getViewFor(locationWidgetId);
        assertEquals(app.getString(R.string.location_widget_empty),
                ((TextView) initialView.findViewById(R.id.tvLocationCoords)).getText().toString());

        WidgetActionReceiver actionReceiver = new WidgetActionReceiver();

        // Direct broadcast to exported LocationWidgetProvider must be ignored
        provider.onReceive(app, new Intent(app, LocationWidgetProvider.class)
                .setAction(LocationWidgetProvider.ACTION_LOCATION_REFRESH));
        awaitRepo();
        assertEquals(0, fakeGateway.fetchGpsCalls.get());

        fakeGateway.currentLocation = new BydGateway.VehicleLocation(-6.20880, 106.84560, 1700000300L);
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(LocationWidgetProvider.ACTION_LOCATION_REFRESH));
        awaitRepo();

        View updatedView = shadowMgr.getViewFor(locationWidgetId);
        assertEquals("-6.20880, 106.84560",
                ((TextView) updatedView.findViewById(R.id.tvLocationCoords)).getText().toString());
        String title = ((TextView) updatedView.findViewById(R.id.tvLocationTitle)).getText().toString();
        assertTrue(title.toUpperCase(Locale.US).contains("ATTO 1"));
        assertTrue(title.contains("Navigate"));

        // Tapping widget body launches NavigationPromptActivity without requiring MainActivity open
        actionReceiver.onReceive(app, new Intent(app, WidgetActionReceiver.class)
                .setAction(LocationWidgetProvider.ACTION_LOCATION_NAVIGATE));
        ShadowLooper.idleMainLooper();

        Intent launchedPrompt = Shadows.shadowOf(app).getNextStartedActivity();
        assertNotNull(launchedPrompt);
        assertEquals(NavigationPromptActivity.class.getName(), launchedPrompt.getComponent().getClassName());
        assertEquals(-6.20880, launchedPrompt.getDoubleExtra(NavigationPromptActivity.EXTRA_LAT, 0), 1e-5);
        assertEquals(106.84560, launchedPrompt.getDoubleExtra(NavigationPromptActivity.EXTRA_LNG, 0), 1e-5);

        // Launch NavigationPromptActivity and verify it asks before opening navigation app chooser
        try (ActivityController<NavigationPromptActivity> controller =
                     Robolectric.buildActivity(NavigationPromptActivity.class, launchedPrompt).setup()) {
            NavigationPromptActivity promptActivity = controller.get();
            AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();

            Intent navChooser = Shadows.shadowOf(promptActivity).getNextStartedActivity();
            assertNotNull(navChooser);
            assertEquals(Intent.ACTION_CHOOSER, navChooser.getAction());
            Intent innerTarget = navChooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertNotNull(innerTarget);
            assertFalse(innerTarget.getDataString().isEmpty());
            assertTrue(innerTarget.getDataString().contains("-6.208800"));
            assertTrue(innerTarget.getDataString().contains("106.845600"));
        }
    }
}
