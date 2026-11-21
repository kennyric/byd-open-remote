package com.bydwatch.companion.lab;

import android.content.Context;
import android.widget.EditText;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverdriveControlActivityTest {

    private static final String CURRENT_URL =
            "http://192.168.1.50:8080/api/vehicle/ac-charge-current-limit";
    private static final String SCHEDULE_URL =
            "http://192.168.1.50:8080/api/vehicle/charging-schedule";
    private static final String START_URL =
            "http://192.168.1.50:8080/api/vehicle/start-charging";
    private static final String TOKEN_URL = "http://192.168.1.50:8080/auth/token";
    private static final String AUTH_STATUS_URL = "http://192.168.1.50:8080/auth/status";

    private static final String CURRENT_MAX =
            "{\"success\":true,\"supported\":true,\"state\":5,\"label\":\"Max\"}";
    private static final String CURRENT_8A =
            "{\"success\":true,\"supported\":true,\"state\":2,\"label\":\"8 A\"}";
    private static final String SCHEDULE_DAILY =
            "{\"success\":true,\"supported\":true,\"enabled\":true,"
                    + "\"startChargeTime\":\"23:00\",\"endChargeTime\":\"06:00\","
                    + "\"chargeWay\":\"e\",\"source\":\"cloud\"}";

    /** Routes each URL to a canned response and records request bodies. */
    private static final class RoutingTransport implements OverdriveClient.Transport {
        final List<String> calls = new ArrayList<>();
        int currentCode = 200;
        String current = CURRENT_MAX;
        int scheduleCode = 200;
        String schedule = SCHEDULE_DAILY;
        int tokenCode = 200;
        String token = "{\"success\":true,\"jwt\":\"jwt-1\",\"expiresIn\":2592000}";
        int startCode = 200;
        String start = "{\"success\":true}";
        String lastPostPath;
        String lastPostBody;

        @Override
        public OverdriveClient.Response execute(String method, String url, String jwt, String body)
                throws IOException {
            calls.add(method + " " + url);
            if ("POST".equals(method)) {
                lastPostPath = url;
                lastPostBody = body;
            }
            // Behave like the car: only the freshly minted token is accepted, so an expired
            // (or otherwise stale) token comes back as 401.
            if (isApiUrl(url) && jwt != null && !"jwt-1".equals(jwt)) {
                return new OverdriveClient.Response(401, "{\"error\":\"unauthorized\"}");
            }
            if (url.equals(AUTH_STATUS_URL)) {
                return new OverdriveClient.Response(200, "{\"deviceId\":\"byd-a1b2c3d4\"}");
            }
            if (url.equals(TOKEN_URL)) return new OverdriveClient.Response(tokenCode, token);
            if (url.equals(CURRENT_URL)) {
                if ("POST".equals(method) && currentCode == 200) {
                    return new OverdriveClient.Response(200, echoCurrent(body));
                }
                return new OverdriveClient.Response(currentCode, current);
            }
            if (url.equals(SCHEDULE_URL)) {
                if ("POST".equals(method) && scheduleCode == 200) {
                    return new OverdriveClient.Response(200, echoSchedule(body));
                }
                return new OverdriveClient.Response(scheduleCode, schedule);
            }
            if (url.equals(START_URL)) return new OverdriveClient.Response(startCode, start);
            return new OverdriveClient.Response(404, "{}");
        }

        private static boolean isApiUrl(String url) {
            return CURRENT_URL.equals(url) || SCHEDULE_URL.equals(url) || START_URL.equals(url);
        }

        private static String echoCurrent(String body) {
            try {
                int state = new JSONObject(body).optInt("state", 0);
                OverdriveApi.CurrentLimit limit =
                        OverdriveApi.parseCurrentLimit("{\"success\":true,\"state\":" + state + "}");
                return "{\"success\":true,\"supported\":true,\"state\":" + state
                        + ",\"label\":\"" + limit.label + "\"}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"bad body\"}";
            }
        }

        private static String echoSchedule(String body) {
            try {
                JSONObject sent = new JSONObject(body);
                return "{\"success\":true,\"supported\":true,"
                        + "\"startChargeTime\":\"" + sent.optString("startChargeTime", "23:00") + "\","
                        + "\"endChargeTime\":\"" + sent.optString("endChargeTime", "06:00") + "\","
                        + "\"chargeWay\":\"" + sent.optString("chargeWay", "e") + "\","
                        + "\"enabled\":" + sent.optBoolean("enabled", true) + "}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"bad body\"}";
            }
        }
    }

    private Context context;
    private RoutingTransport transport;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        BydWatchApp.setTestDependencies(new BydRepositoryTest.InMemoryConfigStore(), null, null);
        CarWebPrefs.reset(context);
        CarWebPrefs.setBaseUrl(context, "192.168.1.50:8080");
        CarWebPrefs.setOverdriveControlsEnabled(context, true);
        transport = new RoutingTransport();
        OverdriveClient.setTransportForTesting(transport);
        OverdriveControlActivity.setExecutorForTesting(Runnable::run);
    }

    @After
    public void tearDown() {
        OverdriveClient.setTransportForTesting(null);
        OverdriveControlActivity.setExecutorForTesting(null);
        BydWatchApp.resetForTesting();
    }

    private OverdriveControlActivity open() {
        return Robolectric.buildActivity(OverdriveControlActivity.class).setup().get();
    }

    @Test
    public void withoutSessionShowsLoginNeededAndDoesNotCallApi() {
        OverdriveControlActivity activity = open();

        assertEquals("Login needed", text(activity, R.id.tvOverdriveControlStatus));
        assertTrue(activity.findViewById(R.id.btnOverdriveControlLogin).getVisibility()
                == android.view.View.VISIBLE);
        for (String call : transport.calls) {
            assertFalse("unexpected API call: " + call, call.contains("/api/vehicle/"));
        }
    }

    @Test
    public void readsCurrentLimitAndSchedule() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());

        OverdriveControlActivity activity = open();

        assertEquals("Connected", text(activity, R.id.tvOverdriveControlStatus));
        MaterialButtonToggleGroup toggle = activity.findViewById(R.id.toggleCurrentLimit);
        assertEquals(R.id.btnCurrentMax, toggle.getCheckedButtonId());

        assertEquals("23:00", text(activity, R.id.btnScheduleStart));
        assertEquals("06:00", text(activity, R.id.btnScheduleEnd));
        MaterialSwitch enabled = activity.findViewById(R.id.switchScheduleEnabled);
        assertTrue(enabled.isChecked());
        MaterialButtonToggleGroup repeat = activity.findViewById(R.id.toggleScheduleRepeat);
        assertEquals(R.id.btnRepeatDaily, repeat.getCheckedButtonId());
        assertTrue(text(activity, R.id.tvScheduleSource).contains("cloud"));
    }

    @Test
    public void expiredSessionClearsJwtAndAsksForLogin() {
        OverdriveSession.setJwt(context, "stale", 2592000L, System.currentTimeMillis());
        transport.currentCode = 401;
        transport.current = "{\"error\":\"unauthorized\"}";

        OverdriveControlActivity activity = open();

        assertEquals("Login needed", text(activity, R.id.tvOverdriveControlStatus));
        assertNull(OverdriveSession.jwt(context));
    }

    @Test
    public void tappingSegmentWritesCurrentLimit() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        ((MaterialButton) activity.findViewById(R.id.btnCurrent8)).performClick();

        assertEquals(CURRENT_URL, transport.lastPostPath);
        assertTrue(transport.lastPostBody.contains("\"state\":2"));
        MaterialButtonToggleGroup toggle = activity.findViewById(R.id.toggleCurrentLimit);
        assertEquals(R.id.btnCurrent8, toggle.getCheckedButtonId());
    }

    @Test
    public void savingSchedulePostsCurrentValues() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        ((MaterialButton) activity.findViewById(R.id.btnScheduleSave)).performClick();

        assertEquals(SCHEDULE_URL, transport.lastPostPath);
        assertTrue(transport.lastPostBody.contains("\"startChargeTime\":\"23:00\""));
        assertTrue(transport.lastPostBody.contains("\"endChargeTime\":\"06:00\""));
        assertTrue(transport.lastPostBody.contains("\"chargeWay\":\"e\""));
    }

    @Test
    public void savingScheduleKeepsControlsUsable() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        ((MaterialButton) activity.findViewById(R.id.btnScheduleSave)).performClick();

        assertTrue("save must stay enabled after a save",
                activity.findViewById(R.id.btnScheduleSave).isEnabled());
        assertTrue("current limit must stay enabled after a save",
                activity.findViewById(R.id.toggleCurrentLimit).isEnabled());
        MaterialButtonToggleGroup repeat = activity.findViewById(R.id.toggleScheduleRepeat);
        assertEquals(R.id.btnRepeatDaily, repeat.getCheckedButtonId());
        assertTrue(text(activity, R.id.tvScheduleSource).contains("cloud"));
    }

    @Test
    public void changingRepeatToOnceSendsOnce() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        ((MaterialButton) activity.findViewById(R.id.btnRepeatOnce)).performClick();
        ((MaterialButton) activity.findViewById(R.id.btnScheduleSave)).performClick();

        assertTrue(transport.lastPostBody.contains("\"chargeWay\":\"s\""));
    }

    @Test
    public void togglingScheduleSwitchPostsEnabledOnly() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        MaterialSwitch toggle = activity.findViewById(R.id.switchScheduleEnabled);
        toggle.setChecked(false);
        toggle.jumpDrawablesToCurrentState();

        assertEquals(SCHEDULE_URL, transport.lastPostPath);
        assertFalse(transport.lastPostBody.contains("startChargeTime"));
        assertTrue(transport.lastPostBody.contains("\"enabled\":false"));
    }

    @Test
    public void startChargingCallsEndpoint() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        OverdriveControlActivity activity = open();

        ((MaterialButton) activity.findViewById(R.id.btnStartCharging)).performClick();

        assertEquals(START_URL, transport.lastPostPath);
    }

    @Test
    public void autoConnectsWithStoredAccessCode() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");

        OverdriveControlActivity activity = open();

        assertTrue(transport.calls.stream().anyMatch(c -> c.equals("POST " + TOKEN_URL)));
        assertNotNull(OverdriveSession.jwt(context));
        assertEquals("Connected", text(activity, R.id.tvOverdriveControlStatus));
    }

    @Test
    public void expiredJwtIsRemintedFromTheStoredAccessCode() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        // Minted an hour ago with a one-minute lifetime: still stored, but no longer valid.
        OverdriveSession.setJwt(context, "stale-token", 60L,
                System.currentTimeMillis() - 3_600_000L);

        OverdriveControlActivity activity = open();

        assertTrue("expired token must be re-minted, calls=" + transport.calls,
                transport.calls.stream().anyMatch(c -> c.equals("POST " + TOKEN_URL)));
        assertEquals("jwt-1", OverdriveSession.jwt(context));
        assertEquals("Connected", text(activity, R.id.tvOverdriveControlStatus));
    }

    @Test
    public void returningToTheScreenRefreshesTheSession() {
        org.robolectric.android.controller.ActivityController<OverdriveControlActivity> controller =
                Robolectric.buildActivity(OverdriveControlActivity.class).setup();
        OverdriveControlActivity activity = controller.get();
        assertEquals("Login needed", text(activity, R.id.tvOverdriveControlStatus));

        // The user connects Overdrive elsewhere (Advanced) and comes back.
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        controller.pause().stop().start().resume();

        assertNotNull(OverdriveSession.jwt(context));
        assertEquals("Connected", text(activity, R.id.tvOverdriveControlStatus));
    }

    @Test
    public void unsupportedCurrentLimitDisablesSegments() {
        OverdriveSession.setJwt(context, "jwt-1", 2592000L, System.currentTimeMillis());
        transport.current = "{\"success\":true,\"supported\":false,\"state\":null}";

        OverdriveControlActivity activity = open();

        MaterialButtonToggleGroup toggle = activity.findViewById(R.id.toggleCurrentLimit);
        assertFalse(toggle.isEnabled());
        assertEquals("The car does not report an adjustable AC current limit.",
                text(activity, R.id.tvCurrentLimitHint));
    }

    private static String text(android.app.Activity activity, int id) {
        return ((android.widget.TextView) activity.findViewById(id)).getText().toString();
    }

    private EditText editText(android.app.Activity activity, int id) {
        return activity.findViewById(id);
    }
}
