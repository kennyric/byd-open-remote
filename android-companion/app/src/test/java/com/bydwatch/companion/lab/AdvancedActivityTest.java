package com.bydwatch.companion.lab;

import android.content.Context;
import android.content.Intent;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.google.android.material.switchmaterial.SwitchMaterial;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdvancedActivityTest {

    private Context context;
    private BydRepositoryTest.InMemoryConfigStore configStore;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        BydWatchApp.setTestDependencies(configStore, null, null);
    }

    @After
    public void tearDown() {
        OverdriveClient.setTransportForTesting(null);
        OverdriveWork.setExecutorForTesting(null);
        BydWatchApp.resetForTesting();
    }

    @Test
    public void savingTopPagePersistsAndFinishes() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        SwitchMaterial show = activity.findViewById(
                com.bydwatch.companion.R.id.switchAdvShowOverdrive);
        show.setChecked(true);

        android.widget.RadioButton vehicle = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvVehicleControl);
        vehicle.setChecked(true);

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertTrue(CarWebPrefs.showOverdrive(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void manualUrlOverridesPreset() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText manual = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvManualUrl);
        manual.setText("http://192.168.1.50:8080/events.html");

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals("http://192.168.1.50:8080/events.html",
                CarWebPrefs.defaultPage(context));
    }

    @Test
    public void loadAllRevealsMorePages() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        android.view.View more = activity.findViewById(
                com.bydwatch.companion.R.id.layoutAdvMorePages);
        assertEquals(android.view.View.GONE, more.getVisibility());

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvLoadAll).performClick();
        assertEquals(android.view.View.VISIBLE, more.getVisibility());
    }

    @Test
    public void resetSelectsVehicleControl() {
        CarWebPrefs.setDefaultPage(context, "/events.html");
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvReset).performClick();

        android.widget.RadioButton vehicle = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvVehicleControl);
        assertTrue(vehicle.isChecked());
    }

    @Test
    public void visibilityChoicePersists() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.RadioButton always = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvControlsAlways);
        always.setChecked(true);
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals(CarWebPrefs.CONTROLS_ALWAYS,
                CarWebPrefs.overdriveControlsVisibility(context));
    }

    @Test
    public void savedVisibilityChoiceIsRestored() {
        CarWebPrefs.setOverdriveControlsVisibility(context, CarWebPrefs.CONTROLS_CONNECTED);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.RadioGroup group = activity.findViewById(
                com.bydwatch.companion.R.id.rgAdvControlsVisibility);
        assertEquals(com.bydwatch.companion.R.id.rbAdvControlsConnected,
                group.getCheckedRadioButtonId());
    }

    @Test
    public void resetHidesOverdriveControls() {
        CarWebPrefs.setOverdriveControlsVisibility(context, CarWebPrefs.CONTROLS_ALWAYS);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvReset).performClick();

        android.widget.RadioGroup group = activity.findViewById(
                com.bydwatch.companion.R.id.rgAdvControlsVisibility);
        assertEquals(com.bydwatch.companion.R.id.rbAdvControlsHidden,
                group.getCheckedRadioButtonId());
    }

    @Test
    public void reachableWithoutSessionShowsLoginNeeded() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withTransport(true);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.TextView status = activity.findViewById(
                com.bydwatch.companion.R.id.tvAdvOverdriveStatus);
        assertEquals("Login needed", status.getText().toString());
        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveLogin)
                        .getVisibility());
    }

    @Test
    public void unreachableCarHidesLoginButton() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withTransport(false);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.TextView status = activity.findViewById(
                com.bydwatch.companion.R.id.tvAdvOverdriveStatus);
        assertEquals("Not reachable", status.getText().toString());
        assertEquals(android.view.View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveLogin)
                        .getVisibility());
    }

    @Test
    public void unreachableCarOffersRetry() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        RecordingTransport transport = withTransport(false);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveRetry)
                        .getVisibility());

        // The car wakes up (or the Wi-Fi reconnects): the button must probe again.
        transport.reachable = true;
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveRetry).performClick();

        assertEquals("Login needed",
                ((android.widget.TextView) activity.findViewById(
                        com.bydwatch.companion.R.id.tvAdvOverdriveStatus)).getText().toString());
    }

    @Test
    public void connectedSessionHidesRetry() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withTransport(true);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        assertEquals(android.view.View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveRetry)
                        .getVisibility());
    }

    @Test
    public void loginNeededStillOffersRetry() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withTransport(true);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveRetry)
                        .getVisibility());
        assertEquals(android.view.View.VISIBLE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveLogin)
                        .getVisibility());
    }

    @Test
    public void connectedSessionShowsConnected() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withTransport(true);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.TextView status = activity.findViewById(
                com.bydwatch.companion.R.id.tvAdvOverdriveStatus);
        assertEquals("Connected", status.getText().toString());
        assertEquals(android.view.View.GONE,
                activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveLogin)
                        .getVisibility());
    }

    @Test
    public void loginButtonOpensOverdriveWebView() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withTransport(true);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveLogin).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(CarWebActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void connectStoresSessionAndShowsConnected() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        withTransport(true);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText code = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvAccessCode);
        code.setText("x7k9m2p5");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvAccessConnect).performClick();

        assertNotNull(OverdriveSession.jwt(context));
        assertEquals("byd-a1b2c3d4", OverdriveSession.deviceId(context));
        assertEquals("x7k9m2p5", OverdriveSession.accessCode(context));
        android.widget.TextView status = activity.findViewById(
                com.bydwatch.companion.R.id.tvAdvOverdriveStatus);
        assertEquals("Connected", status.getText().toString());
    }

    @Test
    public void shortCodeDoesNotReachTheCar() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        RecordingTransport transport = withTransport(true);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        transport.tokenCalls = 0;

        android.widget.EditText code = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvAccessCode);
        code.setText("short");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvAccessConnect).performClick();

        assertEquals(0, transport.tokenCalls);
        assertNull(OverdriveSession.jwt(context));
    }

    @Test
    public void invalidCodeKeepsUserDisconnected() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        RecordingTransport transport = withTransport(true);
        transport.token = "{\"success\":false,\"error\":\"Invalid access code\"}";
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText code = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvAccessCode);
        code.setText("wrong-code");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvAccessConnect).performClick();

        assertNull(OverdriveSession.jwt(context));
        assertEquals("Login needed",
                ((android.widget.TextView) activity.findViewById(
                        com.bydwatch.companion.R.id.tvAdvOverdriveStatus)).getText().toString());
    }

    @Test
    public void disconnectForgetsSession() {
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        OverdriveSession.setJwt(context, "jwt-1", 3600L, System.currentTimeMillis());
        withTransport(true);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvAccessDisconnect).performClick();

        assertNull(OverdriveSession.jwt(context));
        assertFalse(OverdriveSession.hasAccessCode(context));
    }

    @Test
    public void sessionTtlDefaultsToOneTwentyAndPersists() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText ttl = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvSessionTtl);
        assertEquals("120", ttl.getText().toString());

        ttl.setText("90");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals(90, configStore.getSessionTtlMinutes());
    }

    @Test
    public void invalidSessionTtlKeepsTheFormOpen() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText ttl = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvSessionTtl);
        ttl.setText("1");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals(120, configStore.getSessionTtlMinutes());
        assertFalse(activity.isFinishing());
    }

    @Test
    public void savingAPastedLoginPageUrlKeepsItForTheWebView() {
        String pasted = "http://100.84.59.94:8080/login.html?redirect=/index.html";
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText base = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvBaseUrl);
        base.setText(pasted);
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals("http://100.84.59.94:8080/", CarWebPrefs.baseUrl(context));
        assertEquals(pasted, CarWebPrefs.entryUrl(context));
    }

    @Test
    public void pageUrlInPrefsIsProbedAtTheServerRoot() {
        context.getSharedPreferences("car_web", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("car_base_url", "http://100.84.59.94:8080/login.html?redirect=/index.html")
                .commit();
        RecordingTransport transport = withTransport(true);

        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        assertEquals("http://100.84.59.94:8080/auth/status", transport.lastStatusUrl);
        assertEquals("Login needed",
                ((android.widget.TextView) activity.findViewById(
                        com.bydwatch.companion.R.id.tvAdvOverdriveStatus)).getText().toString());
    }

    @Test
    public void microgMapsChoicePersists() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        com.google.android.material.switchmaterial.SwitchMaterial microg =
                activity.findViewById(com.bydwatch.companion.R.id.switchAdvMicrogMaps);
        microg.setChecked(true);
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals(com.bydwatch.companion.data.VehicleStatusBroadcaster.MICROG_TARGET_PACKAGE,
                configStore.getVehicleStatusTargetPackage());
    }

    @Test
    public void microgMapsChoiceSurvivesReopeningAdvancedWithTheRealStore() {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "advanced_real_store_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        com.bydwatch.companion.data.SecureConfigStore realStore =
                new com.bydwatch.companion.data.SecureConfigStore(prefs);
        BydWatchApp.setTestDependencies(realStore, null, null);

        AdvancedActivity first = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        SwitchMaterial microg = first.findViewById(
                com.bydwatch.companion.R.id.switchAdvMicrogMaps);
        microg.setChecked(true);
        first.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        AdvancedActivity reopened = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        SwitchMaterial reread = reopened.findViewById(
                com.bydwatch.companion.R.id.switchAdvMicrogMaps);
        assertTrue("microG choice must survive save and reopen", reread.isChecked());
    }

    @Test
    public void batteryCapacityIsEditableInAdvanced() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText capacity = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvBatteryCapacity);
        capacity.setText("30.08");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals(30.08f, configStore.getBatteryCapacityKwh(), 0.001f);
    }

    @Test
    public void invalidBatteryCapacityIsRejected() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText capacity = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvBatteryCapacity);
        capacity.setText("0");
        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertFalse("invalid capacity must not save and close", activity.isFinishing());
        assertEquals(0f, configStore.getBatteryCapacityKwh(), 0.001f);
    }

    @Test
    public void batteryCapacityLoadsIntoTheField() {
        configStore.setBatteryCapacityKwh(44.9f);
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText capacity = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvBatteryCapacity);
        assertEquals("44.9", capacity.getText().toString());
    }

    @Test
    public void guideButtonOpensTheRemoteAccessGuide() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvOverdriveGuide).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(Intent.ACTION_VIEW, next.getAction());
        assertEquals(activity.getString(com.bydwatch.companion.R.string.overdrive_guide_url),
                next.getDataString());
    }

    /** Fake car: /auth/status answers, the token endpoint mints a JWT. */
    private static final class RecordingTransport implements OverdriveClient.Transport {
        boolean reachable;
        int tokenCalls;
        String lastStatusUrl;
        String token = "{\"success\":true,\"jwt\":\"jwt-1\",\"expiresIn\":2592000}";

        @Override
        public OverdriveClient.Response execute(String method, String url, String jwt, String body)
                throws java.io.IOException {
            if (!reachable) throw new java.io.IOException("no route");
            if (url.endsWith("/auth/status")) {
                lastStatusUrl = url;
                return new OverdriveClient.Response(200, "{\"deviceId\":\"byd-a1b2c3d4\"}");
            }
            if (url.endsWith("/auth/token")) {
                tokenCalls++;
                return new OverdriveClient.Response(200, token);
            }
            return new OverdriveClient.Response(404, "{}");
        }
    }

    private RecordingTransport withTransport(boolean reachable) {
        RecordingTransport transport = new RecordingTransport();
        transport.reachable = reachable;
        OverdriveClient.setTransportForTesting(transport);
        OverdriveWork.setExecutorForTesting(Runnable::run);
        return transport;
    }
}
