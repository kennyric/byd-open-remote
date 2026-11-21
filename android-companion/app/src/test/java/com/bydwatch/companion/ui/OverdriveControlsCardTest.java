package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.lab.CarWebActivity;
import com.bydwatch.companion.lab.CarWebPrefs;
import com.bydwatch.companion.lab.OverdriveClient;
import com.bydwatch.companion.lab.OverdriveControlActivity;
import com.bydwatch.companion.lab.OverdriveSession;
import com.bydwatch.companion.lab.OverdriveWork;

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
public class OverdriveControlsCardTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BydWatchApp.setTestDependencies(new BydRepositoryTest.InMemoryConfigStore(), null, null);
        CarWebPrefs.reset(context);
    }

    @After
    public void tearDown() {
        OverdriveClient.setTransportForTesting(null);
        OverdriveWork.setExecutorForTesting(null);
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
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveControls).getVisibility());
    }

    @Test
    public void cardVisibleWhenEnabledAndConnected() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(R.id.cardOverdriveControls).getVisibility());
    }

    @Test
    public void cardShowsCurrentLimitAndScheduleSummary() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals("Max",
                ((android.widget.TextView) activity.findViewById(R.id.tvOverdriveCurrentSummary))
                        .getText().toString());
        assertEquals("23:00\u201306:00 \u00b7 Every day",
                ((android.widget.TextView) activity.findViewById(R.id.tvOverdriveScheduleSummary))
                        .getText().toString());
        assertEquals("Connected",
                ((android.widget.TextView) activity.findViewById(R.id.tvOverdriveControlsStatus))
                        .getText().toString());
    }

    @Test
    public void cardHiddenWhenLoginNeeded() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveControls).getVisibility());
    }

    @Test
    public void cardHiddenWhenCarUnreachable() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withCar(false);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveControls).getVisibility());
    }

    @Test
    public void cardHiddenWithoutAddress() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        assertEquals(android.view.View.GONE,
                activity.findViewById(R.id.cardOverdriveControls).getVisibility());
    }

    @Test
    public void openButtonStartsNativeControls() {
        seed();
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withCar(true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.btnOpenOverdriveControls).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(OverdriveControlActivity.class.getName(), next.getComponent().getClassName());
    }

    /** A reachable car with a stored session. */
    private void withCar(boolean reachable) {
        OverdriveClient.setTransportForTesting(new OverdriveClient.Transport() {
            @Override
            public OverdriveClient.Response execute(String method, String url, String jwt, String body)
                    throws java.io.IOException {
                if (!reachable) throw new java.io.IOException("no route");
                if (url.endsWith("/auth/status")) {
                    return new OverdriveClient.Response(200, "{\"deviceId\":\"byd-a1b2c3d4\"}");
                }
                if (url.endsWith("/ac-charge-current-limit")) {
                    return new OverdriveClient.Response(200,
                            "{\"success\":true,\"supported\":true,\"state\":5,\"label\":\"Max\"}");
                }
                if (url.endsWith("/charging-schedule")) {
                    return new OverdriveClient.Response(200,
                            "{\"success\":true,\"supported\":true,\"enabled\":true,"
                                    + "\"startChargeTime\":\"23:00\",\"endChargeTime\":\"06:00\","
                                    + "\"chargeWay\":\"e\",\"source\":\"cloud\"}");
                }
                return new OverdriveClient.Response(404, "{}");
            }
        });
        OverdriveWork.setExecutorForTesting(Runnable::run);
    }
}
