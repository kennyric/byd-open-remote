package com.bydwatch.companion.lab;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OverdriveApiTest {

    @Test
    public void buildsAuthStatusUrl() {
        assertEquals("http://192.168.1.50:8080/auth/status",
                OverdriveApi.authStatusUrl("192.168.1.50:8080"));
    }

    @Test
    public void buildsTokenUrlFromBaseWithTrailingSlash() {
        assertEquals("http://192.168.1.50:8080/auth/token",
                OverdriveApi.tokenUrl("http://192.168.1.50:8080/"));
    }

    @Test
    public void returnsEmptyUrlForBlankBase() {
        assertEquals("", OverdriveApi.authStatusUrl(""));
        assertEquals("", OverdriveApi.currentLimitUrl("  "));
    }

    @Test
    public void buildsVehicleApiUrls() {
        assertEquals("http://car.local:8080/api/vehicle/ac-charge-current-limit",
                OverdriveApi.currentLimitUrl("car.local:8080"));
        assertEquals("http://car.local:8080/api/vehicle/charging-schedule",
                OverdriveApi.scheduleUrl("car.local:8080"));
        assertEquals("http://car.local:8080/api/vehicle/start-charging",
                OverdriveApi.startChargingUrl("car.local:8080"));
    }

    @Test
    public void deviceTokenJoinsDeviceIdAndAccessCode() {
        assertEquals("byd-a1b2c3d4-x7k9m2p5",
                OverdriveApi.deviceToken("byd-a1b2c3d4", "x7k9m2p5"));
    }

    @Test
    public void deviceTokenLowercasesAndTrimsAccessCode() {
        assertEquals("byd-a1b2c3d4-x7k9m2p5",
                OverdriveApi.deviceToken("byd-a1b2c3d4", "  X7K9M2P5 "));
    }

    @Test
    public void parsesDeviceIdFromAuthStatus() {
        assertEquals("byd-a1b2c3d4",
                OverdriveApi.parseDeviceId("{\"status\":\"ok\",\"deviceId\":\"byd-a1b2c3d4\"}"));
    }

    @Test
    public void unknownDeviceIdIsNotUsable() {
        assertNull(OverdriveApi.parseDeviceId("{\"status\":\"ok\",\"deviceId\":\"unknown\"}"));
        assertNull(OverdriveApi.parseDeviceId("{}"));
    }

    @Test
    public void parsesJwtFromTokenResponse() {
        assertEquals("header.payload.sig", OverdriveApi.parseJwt(
                "{\"success\":true,\"jwt\":\"header.payload.sig\",\"expiresIn\":2592000}"));
        assertNull(OverdriveApi.parseJwt("{\"success\":false,\"error\":\"bad code\"}"));
    }

    @Test
    public void mapsStatesToLabels() {
        assertEquals("6 A", OverdriveApi.labelForState(1));
        assertEquals("8 A", OverdriveApi.labelForState(2));
        assertEquals("10 A", OverdriveApi.labelForState(3));
        assertEquals("16 A", OverdriveApi.labelForState(4));
        assertEquals("Max", OverdriveApi.labelForState(5));
        assertNull(OverdriveApi.labelForState(0));
    }

    @Test
    public void mapsLabelsBackToStates() {
        assertEquals(1, OverdriveApi.stateForLabel("6 A"));
        assertEquals(4, OverdriveApi.stateForLabel("16 A"));
        assertEquals(5, OverdriveApi.stateForLabel("Max"));
        assertEquals(0, OverdriveApi.stateForLabel("nope"));
    }

    @Test
    public void parsesUnsupportedCurrentLimit() {
        OverdriveApi.CurrentLimit limit = OverdriveApi.parseCurrentLimit(
                "{\"success\":true,\"supported\":false,\"state\":null}");
        assertFalse(limit.supported);
        assertEquals(0, limit.state);
        assertNull(limit.label);
    }

    @Test
    public void parsesCurrentLimitState() {
        OverdriveApi.CurrentLimit limit = OverdriveApi.parseCurrentLimit(
                "{\"success\":true,\"supported\":true,\"state\":5,\"label\":\"MAX\"}");
        assertTrue(limit.supported);
        assertEquals(5, limit.state);
        assertEquals("Max", limit.label);
    }

    @Test
    public void parsesSchedule() {
        OverdriveApi.Schedule schedule = OverdriveApi.parseSchedule(
                "{\"success\":true,\"supported\":true,\"enabled\":true,"
                        + "\"startChargeTime\":\"23:00\",\"endChargeTime\":\"06:00\","
                        + "\"chargeWay\":\"e\",\"source\":\"cloud\"}");
        assertTrue(schedule.supported);
        assertTrue(schedule.enabled);
        assertEquals("23:00", schedule.start);
        assertEquals("06:00", schedule.end);
        assertEquals(OverdriveApi.Repeat.DAILY, schedule.repeat);
    }

    @Test
    public void mapsRepeatToChargeWay() {
        assertEquals("s", OverdriveApi.chargeWayFor(OverdriveApi.Repeat.ONCE));
        assertEquals("e", OverdriveApi.chargeWayFor(OverdriveApi.Repeat.DAILY));
        assertEquals("0,1,2,3,4", OverdriveApi.chargeWayFor(OverdriveApi.Repeat.WEEKDAYS));
    }

    @Test
    public void mapsChargeWayToRepeat() {
        assertEquals(OverdriveApi.Repeat.ONCE, OverdriveApi.repeatFor("s"));
        assertEquals(OverdriveApi.Repeat.DAILY, OverdriveApi.repeatFor("e"));
        assertEquals(OverdriveApi.Repeat.WEEKDAYS, OverdriveApi.repeatFor("0,1,2,3,4"));
        assertEquals(OverdriveApi.Repeat.WEEKDAYS, OverdriveApi.repeatFor("5"));
        assertNull(OverdriveApi.repeatFor(null));
    }

    @Test
    public void buildsScheduleBody() throws Exception {
        JSONObject body = new JSONObject(OverdriveApi.scheduleBodyJson(
                "23:00", "06:00", OverdriveApi.Repeat.DAILY, true));
        assertEquals("23:00", body.getString("startChargeTime"));
        assertEquals("06:00", body.getString("endChargeTime"));
        assertEquals("e", body.getString("chargeWay"));
        assertTrue(body.getBoolean("enabled"));
    }

    @Test
    public void keepsFullEndTimeSentinel() {
        OverdriveApi.Schedule schedule = OverdriveApi.parseSchedule(
                "{\"success\":true,\"supported\":true,\"enabled\":false,"
                        + "\"startChargeTime\":\"22:30\",\"endChargeTime\":\"full\","
                        + "\"chargeWay\":\"s\"}");
        assertTrue(schedule.endIsFull());
        assertEquals("Full", schedule.endLabel());
    }

    @Test
    public void reportsHttpAuthFailureAsLoginNeeded() {
        assertTrue(OverdriveApi.isAuthFailure(401));
        assertTrue(OverdriveApi.isAuthFailure(403));
        assertFalse(OverdriveApi.isAuthFailure(500));
        assertFalse(OverdriveApi.isAuthFailure(200));
    }

    @Test
    public void keepsWhereTheScheduleWasRead() {
        OverdriveApi.Schedule schedule = OverdriveApi.parseSchedule(
                "{\"success\":true,\"supported\":true,\"enabled\":true,"
                        + "\"startChargeTime\":\"23:00\",\"endChargeTime\":\"06:00\","
                        + "\"chargeWay\":\"e\",\"source\":\"cache\"}");
        assertEquals("cache", schedule.source);
        assertNull(OverdriveApi.parseSchedule("{}").source);
    }
}
