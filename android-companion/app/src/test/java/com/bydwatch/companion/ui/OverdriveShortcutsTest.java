package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.lab.CarWebActivity;
import com.bydwatch.companion.lab.CarWebPrefs;
import com.bydwatch.companion.lab.OverdriveConnection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverdriveShortcutsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
        OverdriveConnection.setStatusForTesting(null);
    }

    @After
    public void tearDown() {
        OverdriveConnection.setStatusForTesting(null);
        BydWatchApp.resetForTesting();
    }

    private void seed() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setUsername("test@byd.id");
        store.setPassword("ValidPass123");
        store.setPin("123456");
        store.setRegion("id");
        store.setVin("VIN_TEST");
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
    }

    @Test
    public void cardHiddenWhenToggleOff() {
        seed();
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setStatusForTesting(OverdriveConnection.Status.CONNECTED);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveShortcuts).getVisibility());
    }

    @Test
    public void cardVisibleWhenEnabledAndConnected() {
        seed();
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setStatusForTesting(OverdriveConnection.Status.CONNECTED);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(R.id.cardOverdriveShortcuts).getVisibility());
    }

    @Test
    public void cardHiddenWhenLoginNeeded() {
        seed();
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setStatusForTesting(OverdriveConnection.Status.LOGIN_REQUIRED);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveShortcuts).getVisibility());
    }

    @Test
    public void scheduleButtonOpensDeepLink() {
        seed();
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        OverdriveConnection.setStatusForTesting(OverdriveConnection.Status.CONNECTED);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.btnOverdriveSchedule).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(CarWebActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("/vehicle-control.html", next.getStringExtra(CarWebActivity.EXTRA_PAGE));
        assertEquals("panelCharging", next.getStringExtra(CarWebActivity.EXTRA_SCROLL_ID));
    }
}
