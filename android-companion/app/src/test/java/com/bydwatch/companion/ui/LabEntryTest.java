package com.bydwatch.companion.ui;

import android.content.Intent;
import android.view.MenuItem;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.lab.CarWebActivity;
import com.bydwatch.companion.lab.LabActivity;
import com.google.android.material.navigation.NavigationView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabEntryTest {

    @After
    public void tearDown() {
        BydWatchApp.resetForTesting();
    }

    @Test
    public void drawerShowsLabAndOpensActivity() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setUsername("test@byd.id");
        store.setPassword("ValidPass123");
        store.setPin("123456");
        store.setRegion("id");
        store.setVin("VIN_TEST");
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        NavigationView nav = activity.findViewById(R.id.navView);
        MenuItem item = nav.getMenu().findItem(R.id.nav_lab);
        assertNotNull(item);
        assertTrue(item.isVisible());

        nav.getMenu().performIdentifierAction(R.id.nav_lab, 0);

        ShadowActivity shadow = Shadows.shadowOf(activity);
        Intent next = shadow.getNextStartedActivity();
        assertNotNull(next);
        assertEquals(LabActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void drawerShowsCarWebAndOpensActivity() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setUsername("test@byd.id");
        store.setPassword("ValidPass123");
        store.setPin("123456");
        store.setRegion("id");
        store.setVin("VIN_TEST");
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        NavigationView nav = activity.findViewById(R.id.navView);
        MenuItem item = nav.getMenu().findItem(R.id.nav_car_web);
        assertNotNull(item);
        assertTrue(item.isVisible());

        nav.getMenu().performIdentifierAction(R.id.nav_car_web, 0);

        ShadowActivity shadow = Shadows.shadowOf(activity);
        Intent next = shadow.getNextStartedActivity();
        assertNotNull(next);
        assertEquals(CarWebActivity.class.getName(), next.getComponent().getClassName());
    }
}
