package com.bydwatch.companion.ui;

import android.app.Dialog;
import android.view.View;
import android.widget.TextView;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.model.VehicleSummary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The garage UI: header switcher and the Account page's Garage card. Both read the vehicle list
 * that Fetch VIN stored, so the user can see how many cars the account actually returned.
 */
@RunWith(RobolectricTestRunner.class)
public class GarageUiTest {

    private static final String VIN_ATTO = "LC0TESTVIN1234567";
    private static final String VIN_SEAL = "LC0TESTVIN7654321";

    private BydRepositoryTest.InMemoryConfigStore configStore;
    private BydRepositoryTest.FakeBydGateway gateway;
    private BydRepository repository;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("testuser@byd.id");
        configStore.setPassword("ValidPass123");
        configStore.setPin("123456");
        configStore.setVin(VIN_ATTO);
        configStore.setRegion("id");
        gateway = new BydRepositoryTest.FakeBydGateway();
        repository = new BydRepository(gateway, configStore);
        BydWatchApp.setTestDependencies(configStore, gateway, repository);
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

    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        try {
            repository.getExecutor().submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }

    private void seedTwoCars() {
        List<VehicleSummary> cars = new ArrayList<>();
        cars.add(new VehicleSummary(VIN_ATTO, "BYD Atto 1", "", "EV"));
        cars.add(new VehicleSummary(VIN_SEAL, "BYD Seal", "", "EV"));
        configStore.setVehicles(cars);
    }

    private void openGarageSheet() {
        View switcher = activity.findViewById(R.id.layoutHeaderVehicle);
        assertNotNull(switcher);
        assertTrue(switcher.isClickable());
        switcher.performClick();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void headerSwitcherHiddenWithOneCar() {
        launch();
        View chevron = activity.findViewById(R.id.tvHeaderChevron);
        View switcher = activity.findViewById(R.id.layoutHeaderVehicle);
        assertNotNull(chevron);
        assertEquals(View.GONE, chevron.getVisibility());
        assertFalse(switcher.isClickable());
    }

    @Test
    public void headerSwitcherAppearsWhenAccountHasTwoCars() {
        seedTwoCars();
        launch();
        View chevron = activity.findViewById(R.id.tvHeaderChevron);
        View switcher = activity.findViewById(R.id.layoutHeaderVehicle);
        assertEquals(View.VISIBLE, chevron.getVisibility());
        assertTrue(switcher.isClickable());
    }

    @Test
    public void garageCardHiddenUntilVehiclesAreFetched() {
        launch();
        activity.findViewById(R.id.btnHamburger).performClick();
        activity.showPage(R.id.nav_account);
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, activity.findViewById(R.id.cardGarage).getVisibility());
    }

    @Test
    public void garageCardListsEveryFetchedVehicle() {
        seedTwoCars();
        launch();
        activity.findViewById(R.id.btnHamburger).performClick();
        activity.showPage(R.id.nav_account);
        ShadowLooper.idleMainLooper();

        View card = activity.findViewById(R.id.cardGarage);
        assertEquals(View.VISIBLE, card.getVisibility());

        TextView count = activity.findViewById(R.id.tvGarageCount);
        assertTrue("count should advertise both cars, was: " + count.getText(),
                count.getText().toString().contains("2"));

        android.widget.LinearLayout rows = activity.findViewById(R.id.layoutGarageRows);
        assertEquals(2, rows.getChildCount());

        List<String> names = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView name = rows.getChildAt(i).findViewById(R.id.tvGarageRowName);
            names.add(name.getText().toString());
        }
        assertTrue("expected Atto row, got " + names, names.get(0).toUpperCase().contains("ATTO"));
        assertTrue("expected Seal row, got " + names, names.get(1).toUpperCase().contains("SEAL"));
    }

    @Test
    public void garageRowsMarkTheActiveCarWithACheckedRadio() {
        seedTwoCars();
        launch();
        activity.showPage(R.id.nav_account);
        ShadowLooper.idleMainLooper();

        android.widget.LinearLayout rows = activity.findViewById(R.id.layoutGarageRows);
        android.widget.RadioButton first = rows.getChildAt(0).findViewById(R.id.rbGarageRow);
        android.widget.RadioButton second = rows.getChildAt(1).findViewById(R.id.rbGarageRow);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue("active car's radio should be checked", first.isChecked());
        assertFalse("inactive car's radio should be unchecked", second.isChecked());
    }

    @Test
    public void renamingOneGarageCarStoresItPerVinWithoutTouchingTheOther() {
        seedTwoCars();
        launch();
        activity.showPage(R.id.nav_account);
        ShadowLooper.idleMainLooper();

        android.widget.LinearLayout rows = activity.findViewById(R.id.layoutGarageRows);
        View renameSeal = rows.getChildAt(1).findViewById(R.id.btnGarageRowRename);
        assertNotNull(renameSeal);
        assertEquals(View.VISIBLE, renameSeal.getVisibility());
        renameSeal.performClick();
        ShadowLooper.idleMainLooper();

        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        android.widget.EditText input = dialog.findViewById(R.id.etGarageRename);
        assertNotNull(input);
        input.setText("My Seal");
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("My Seal", configStore.getVehicleCustomName(VIN_SEAL));
        assertEquals("", configStore.getVehicleCustomName(VIN_ATTO));

        TextView header = activity.findViewById(R.id.tvHeaderTitle);
        assertFalse("renaming the inactive car must not change the header",
                header.getText().toString().contains("My Seal"));
    }

    @Test
    public void headerShowsTheActiveCarsOwnName() {
        configStore.setVehicleCustomName(VIN_SEAL, "My Seal");
        configStore.setVin(VIN_SEAL);
        seedTwoCars();
        launch();
        TextView header = activity.findViewById(R.id.tvHeaderTitle);
        assertEquals("My Seal", header.getText().toString());
    }

    @Test
    public void resetClearsTheCarsCustomName() {
        configStore.setVehicleCustomName(VIN_ATTO, "Old Name");
        seedTwoCars();
        launch();
        activity.showPage(R.id.nav_account);
        ShadowLooper.idleMainLooper();

        android.widget.LinearLayout rows = activity.findViewById(R.id.layoutGarageRows);
        rows.getChildAt(0).findViewById(R.id.btnGarageRowRename).performClick();
        ShadowLooper.idleMainLooper();

        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("", configStore.getVehicleCustomName(VIN_ATTO));
    }

    @Test
    public void headerTapTargetIncludesTheCarIcon() {
        seedTwoCars();
        launch();
        android.view.ViewGroup header = activity.findViewById(R.id.layoutHeaderVehicle);
        assertNotNull(header.findViewById(R.id.ivBrandLogo));
        assertTrue(header.isClickable());
    }

    @Test
    public void tappingHeaderOpensGarageSheetWithAllCars() {
        seedTwoCars();
        launch();
        openGarageSheet();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        android.widget.LinearLayout rows = dialog.findViewById(R.id.layoutGarageSheetRows);
        assertNotNull(rows);
        assertEquals(2, rows.getChildCount());
    }

    @Test
    public void tappingSecondCarInSheetSwitchesActiveVehicle() {
        seedTwoCars();
        launch();
        openGarageSheet();

        Dialog dialog = ShadowDialog.getLatestDialog();
        android.widget.LinearLayout rows = dialog.findViewById(R.id.layoutGarageSheetRows);
        rows.getChildAt(1).performClick();
        ShadowLooper.idleMainLooper();
        try {
            repository.getExecutor().submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();

        assertEquals(VIN_SEAL, configStore.getVin());
    }
}
