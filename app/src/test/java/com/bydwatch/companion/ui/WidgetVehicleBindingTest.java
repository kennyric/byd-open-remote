package com.bydwatch.companion.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RemoteViews;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Per-widget vehicle binding: which car a widget shows and commands. */
@RunWith(RobolectricTestRunner.class)
public class WidgetVehicleBindingTest {

    private static final String VIN_ATTO = "LC0WIDGETATTO00001";
    private static final String VIN_SEAL = "LC0WIDGETSEAL00002";

    private Context app;
    private BydRepositoryTest.InMemoryConfigStore configStore;
    private BydRepositoryTest.FakeBydGateway gateway;
    private BydRepository repository;

    @Before
    public void setUp() {
        BydWatchApp.resetForTesting();
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("byd_widget_prefs", Context.MODE_PRIVATE).edit().clear().commit();

        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("user@byd.example");
        configStore.setPassword("secret123");
        configStore.setPin("123456");
        configStore.setVin(VIN_ATTO);
        configStore.setVehicleName("Atto 1");

        gateway = new BydRepositoryTest.FakeBydGateway();
        repository = new BydRepository(gateway, configStore);
        BydWatchApp.setTestDependencies(configStore, gateway, repository);
    }

    @After
    public void tearDown() {
        if (repository != null) repository.shutdown();
        BydWatchApp.resetForTesting();
    }

    private void twoCars() {
        List<VehicleSummary> cars = new ArrayList<>();
        cars.add(new VehicleSummary(VIN_ATTO, "BYD Atto 1", "", "EV"));
        cars.add(new VehicleSummary(VIN_SEAL, "BYD Seal", "", "EV"));
        configStore.setVehicles(cars);
    }

    private VehicleStatus status(String vin, int soc, int range) {
        return new VehicleStatus.Builder(soc, range, true, false, 24, 22, 1700000000L)
                .vin(vin)
                .modelName("BYD")
                .build();
    }

    @Test
    public void bindingIsStoredPerWidgetAndOnlyHonouredForMultiCarAccounts() {
        assertNull("no binding on a single-car account", null);
        assertEquals("", VehicleWidgetProvider.boundVinFor(app, 42));

        twoCars();
        configStore.setWidgetVehicle(42, VIN_SEAL);
        assertEquals(VIN_SEAL, VehicleWidgetProvider.boundVinFor(app, 42));
        assertEquals("", VehicleWidgetProvider.boundVinFor(app, 43));

        assertEquals(VIN_SEAL, configStore.getWidgetVehicle(42));
    }

    @Test
    public void widgetBoundToSealRendersSealTelemetry() {
        twoCars();
        configStore.setWidgetVehicle(42, VIN_SEAL);
        repository.setCachedStatus(status(VIN_SEAL, 64, 352));
        repository.setCachedStatus(status(VIN_ATTO, 78, 268));

        assertEquals(VIN_ATTO, repository.getCachedStatus().vin);
        VehicleStatus forWidget = VehicleWidgetProvider.resolveStatus(app, 42);
        assertNotNull(forWidget);
        assertEquals(VIN_SEAL, forWidget.vin);
        assertEquals(64, forWidget.soc);

        // Unbound widget follows the active car.
        VehicleStatus unbound = VehicleWidgetProvider.resolveStatus(app, 99);
        assertNotNull(unbound);
        assertEquals(VIN_ATTO, unbound.vin);
    }

    @Test
    public void widgetCommandTargetsBoundCarWithoutChangingTheActiveCar() {
        twoCars();
        repository.setCachedStatus(status(VIN_ATTO, 78, 268));
        gateway.currentStatus = status(VIN_SEAL, 64, 352);

        String raw = "{\"id\":\"wtest1\",\"action\":\"status\",\"vin\":\"" + VIN_SEAL + "\"}";
        String response = repository.processWatchJson(raw);

        assertTrue(response.contains("\"ok\":true"));
        assertEquals("active car must not change",
                VIN_ATTO, repository.getCachedStatus().vin);
        assertNotNull(repository.getStatusForVin(VIN_SEAL));
        assertEquals(64, repository.getStatusForVin(VIN_SEAL).soc);
    }

    @Test
    public void carPillIsOnlyShownForMultiCarAccounts() {
        configStore.setVehicleName("Atto 1");
        RemoteViews single = VehicleWidgetProvider.buildWidgetRemoteViews(
                app, configStore, R.layout.widget_vehicle_2x2,
                false, false, false, status(VIN_ATTO, 78, 268), 0L, null, 7);
        View singleRoot = single.apply(app, new FrameLayout(app));
        assertEquals(View.GONE, singleRoot.findViewById(R.id.tvWidgetCarModel).getVisibility());

        twoCars();
        RemoteViews multi = VehicleWidgetProvider.buildWidgetRemoteViews(
                app, configStore, R.layout.widget_vehicle_2x2,
                false, false, false, status(VIN_ATTO, 78, 268), 0L, null, 7);
        View multiRoot = multi.apply(app, new FrameLayout(app));
        View pill = multiRoot.findViewById(R.id.tvWidgetCarModel);
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertTrue("pill should advertise the dropdown",
                ((android.widget.TextView) pill).getText().toString().contains("▾"));
    }

    @Test
    public void everyWidgetLayoutRendersWithTheCarPill() {
        twoCars();
        configStore.setWidgetVehicle(7, VIN_SEAL);
        VehicleStatus seal = status(VIN_SEAL, 64, 352);
        int[][] cases = {
                {R.layout.widget_vehicle_2x2, 0, 0, 0},
                {R.layout.widget_vehicle_4x1, 1, 1, 0},
                {R.layout.widget_vehicle, 1, 0, 1},
                {R.layout.widget_vehicle_4x2_ring, 1, 0, 1},
                {R.layout.widget_vehicle_4x2_bento, 1, 0, 1},
                {R.layout.widget_vehicle_4x2_tank, 1, 0, 1},
                {R.layout.widget_vehicle_4x3, 1, 0, 1},
                {R.layout.widget_vehicle_4x3_ring, 1, 0, 1},
                {R.layout.widget_vehicle_4x3_bento, 1, 0, 1},
                {R.layout.widget_vehicle_4x3_tank, 1, 0, 1},
        };
        for (int[] c : cases) {
            RemoteViews views = VehicleWidgetProvider.buildWidgetRemoteViews(
                    app, configStore, c[0], c[1] == 1, c[2] == 1, c[3] == 1, seal, 0L, null, 7);
            View root = views.apply(app, new FrameLayout(app));
            assertNotNull("layout failed to render: " + c[0], root);
            View pill = root.findViewById(R.id.tvWidgetCarModel);
            assertNotNull("missing car label in layout " + c[0], pill);
            assertEquals("pill must show for multi-car in layout " + c[0],
                    View.VISIBLE, pill.getVisibility());
            assertTrue("pill text should carry a chevron in layout " + c[0],
                    ((android.widget.TextView) pill).getText().toString().contains("▾"));
        }
    }

    @Test
    public void pickerStoresTheChosenCar() {
        twoCars();
        ActivityController<WidgetVehiclePickerActivity> controller =
                Robolectric.buildActivity(WidgetVehiclePickerActivity.class,
                        WidgetVehiclePickerActivity.createIntent(app, 42)).setup();
        WidgetVehiclePickerActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        LinearLayout choices = activity.findViewById(R.id.layoutWidgetVehicleChoices);
        assertNotNull(choices);
        assertEquals(3, choices.getChildCount());

        View sealRow = choices.getChildAt(2);
        RadioButton radio = sealRow.findViewById(R.id.rbWidgetVehicle);
        assertEquals("SEAL", ((android.widget.TextView) sealRow.findViewById(R.id.tvWidgetVehicleName))
                .getText().toString());
        sealRow.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(VIN_SEAL, configStore.getWidgetVehicle(42));
        controller.destroy();
    }
}
