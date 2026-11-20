package com.bydwatch.companion.lab;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OverdriveClientTest {

    private static final String BASE = "192.168.1.50:8080";

    /** Records every request and answers from a fixed script. */
    private static final class FakeTransport implements OverdriveClient.Transport {
        final List<String> calls = new ArrayList<>();
        int code = 200;
        String body = "{}";
        IOException failure;
        String lastJwt;
        String lastBody;

        @Override
        public OverdriveClient.Response execute(String method, String url, String jwt, String body) 
                throws IOException {
            calls.add(method + " " + url);
            lastJwt = jwt;
            lastBody = body;
            if (failure != null) throw failure;
            return new OverdriveClient.Response(code, this.body);
        }
    }

    private FakeTransport transport;

    @Before
    public void setUp() {
        transport = new FakeTransport();
        OverdriveClient.setTransportForTesting(transport);
    }

    @After
    public void tearDown() {
        OverdriveClient.setTransportForTesting(null);
    }

    @Test
    public void loginPostsDeviceTokenAndReturnsJwt() {
        transport.body = "{\"success\":true,\"jwt\":\"a.b.c\",\"expiresIn\":2592000}";

        OverdriveClient.Outcome<String> outcome =
                OverdriveClient.login(BASE, "byd-a1b2c3d4", "x7k9m2p5");

        assertTrue(outcome.ok());
        assertEquals("a.b.c", outcome.value);
        assertEquals("POST http://192.168.1.50:8080/auth/token", transport.calls.get(0));
        assertTrue(transport.lastBody.contains("\"token\":\"byd-a1b2c3d4-x7k9m2p5\""));
    }

    @Test
    public void loginWithoutJwtIsRejected() {
        transport.body = "{\"success\":false,\"error\":\"Invalid access code\"}";

        OverdriveClient.Outcome<String> outcome =
                OverdriveClient.login(BASE, "byd-a1b2c3d4", "wrong-code");

        assertFalse(outcome.ok());
        assertNull(outcome.value);
        assertEquals("Invalid access code", outcome.error);
        assertFalse(outcome.authFailed());
    }

    @Test
    public void transportFailureIsUnreachable() {
        transport.failure = new IOException("timeout");

        OverdriveClient.Outcome<String> outcome =
                OverdriveClient.login(BASE, "byd-a1b2c3d4", "x7k9m2p5");

        assertFalse(outcome.ok());
        assertTrue(outcome.unreachable());
        assertEquals("timeout", outcome.error);
    }

    @Test
    public void readsCurrentLimitWithBearerToken() {
        transport.body = "{\"success\":true,\"supported\":true,\"state\":4,\"label\":\"16 A\"}";

        OverdriveClient.Outcome<OverdriveApi.CurrentLimit> outcome =
                OverdriveClient.currentLimit(BASE, "jwt-1");

        assertTrue(outcome.ok());
        assertEquals(4, outcome.value.state);
        assertEquals("16 A", outcome.value.label);
        assertEquals("jwt-1", transport.lastJwt);
        assertEquals("GET http://192.168.1.50:8080/api/vehicle/ac-charge-current-limit",
                transport.calls.get(0));
    }

    @Test
    public void expiredSessionIsReportedAsAuthFailure() {
        transport.code = 401;
        transport.body = "{\"error\":\"unauthorized\"}";

        OverdriveClient.Outcome<OverdriveApi.CurrentLimit> outcome =
                OverdriveClient.currentLimit(BASE, "stale");

        assertFalse(outcome.ok());
        assertTrue(outcome.authFailed());
        assertFalse(outcome.unreachable());
    }

    @Test
    public void writesCurrentLimitState() {
        transport.body = "{\"success\":true,\"state\":5,\"label\":\"Max\"}";

        OverdriveClient.Outcome<OverdriveApi.CurrentLimit> outcome =
                OverdriveClient.setCurrentLimit(BASE, "jwt-1", 5);

        assertTrue(outcome.ok());
        assertEquals(5, outcome.value.state);
        assertEquals("POST http://192.168.1.50:8080/api/vehicle/ac-charge-current-limit",
                transport.calls.get(0));
        assertTrue(transport.lastBody.contains("\"state\":5"));
    }

    @Test
    public void writeWithoutConfirmationIsFailure() {
        transport.body = "{\"success\":false,\"error\":\"readback mismatch\"}";

        OverdriveClient.Outcome<OverdriveApi.CurrentLimit> outcome =
                OverdriveClient.setCurrentLimit(BASE, "jwt-1", 3);

        assertFalse(outcome.ok());
        assertEquals("readback mismatch", outcome.error);
    }

    @Test
    public void readsSchedule() {
        transport.body = "{\"success\":true,\"supported\":true,\"enabled\":true,"
                + "\"startChargeTime\":\"23:00\",\"endChargeTime\":\"06:00\","
                + "\"chargeWay\":\"e\",\"source\":\"cloud\"}";

        OverdriveClient.Outcome<OverdriveApi.Schedule> outcome =
                OverdriveClient.schedule(BASE, "jwt-1");

        assertTrue(outcome.ok());
        assertEquals("23:00", outcome.value.start);
        assertEquals(OverdriveApi.Repeat.DAILY, outcome.value.repeat);
    }

    @Test
    public void writesScheduleBody() {
        transport.body = "{\"success\":true,\"startChargeTime\":\"22:00\","
                + "\"endChargeTime\":\"05:30\",\"chargeWay\":\"s\",\"enabled\":true}";

        OverdriveClient.Outcome<OverdriveApi.Schedule> outcome = OverdriveClient.setSchedule(
                BASE, "jwt-1", "22:00", "05:30", OverdriveApi.Repeat.ONCE, true);

        assertTrue(outcome.ok());
        assertEquals("22:00", outcome.value.start);
        assertEquals(OverdriveApi.Repeat.ONCE, outcome.value.repeat);
        JSONObject sent = json(transport.lastBody);
        assertEquals("22:00", sent.optString("startChargeTime"));
        assertEquals("05:30", sent.optString("endChargeTime"));
        assertEquals("s", sent.optString("chargeWay"));
        assertTrue(sent.optBoolean("enabled"));
    }

    @Test
    public void toggleOnlyScheduleSendsEnabledFlag() {
        transport.body = "{\"success\":true,\"enabled\":false}";

        OverdriveClient.Outcome<OverdriveApi.Schedule> outcome =
                OverdriveClient.setScheduleEnabled(BASE, "jwt-1", false);

        assertTrue(outcome.ok());
        JSONObject sent = json(transport.lastBody);
        assertFalse(sent.has("startChargeTime"));
        assertFalse(sent.optBoolean("enabled", true));
    }

    @Test
    public void startsCharging() {
        transport.body = "{\"success\":true,\"outcome\":\"success\"}";

        OverdriveClient.Outcome<String> outcome = OverdriveClient.startCharging(BASE, "jwt-1");

        assertTrue(outcome.ok());
        assertEquals("POST http://192.168.1.50:8080/api/vehicle/start-charging",
                transport.calls.get(0));
    }

    @Test
    public void probeReadsAuthStatus() {
        transport.body = "{\"status\":\"ok\",\"deviceId\":\"byd-a1b2c3d4\"}";

        assertTrue(OverdriveClient.isReachable(BASE));
        assertEquals("GET http://192.168.1.50:8080/auth/status", transport.calls.get(0));
        assertNull(transport.lastJwt);
    }

    @Test
    public void probeFailsWhenTransportFails() {
        transport.failure = new IOException("no route");
        assertFalse(OverdriveClient.isReachable(BASE));
    }

    @Test
    public void readsDeviceIdFromAuthStatus() {
        transport.body = "{\"status\":\"ok\",\"deviceId\":\"byd-a1b2c3d4\"}";
        assertEquals("byd-a1b2c3d4", OverdriveClient.deviceId(BASE));
    }

    private static JSONObject json(String body) {
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new AssertionError("not json: " + body, e);
        }
    }
}
