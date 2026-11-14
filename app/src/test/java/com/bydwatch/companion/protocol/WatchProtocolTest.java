package com.bydwatch.companion.protocol;

import com.bydwatch.companion.model.RegionOption;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WatchProtocolTest {

    @Test
    public void parseAcOnDefaultsTempTo17AndClampsOutOfRange() {
        // Missing tempC -> defaults to 17
        WatchCommand cmdDefault = WatchProtocol.parseCommand("{\"id\":\"1\",\"action\":\"ac_on\"}");
        assertEquals("1", cmdDefault.id);
        assertEquals("ac_on", cmdDefault.action);
        assertEquals(17, cmdDefault.tempC);

        // tempC < 17 (e.g. 10) -> clamped to 17
        WatchCommand cmdLow = WatchProtocol.parseCommand("{\"id\":\"2\",\"action\":\"ac_on\",\"tempC\":10}");
        assertEquals("2", cmdLow.id);
        assertEquals("ac_on", cmdLow.action);
        assertEquals(17, cmdLow.tempC);

        // tempC > 31 (e.g. 40) -> clamped to 31
        WatchCommand cmdHigh = WatchProtocol.parseCommand("{\"id\":\"3\",\"action\":\"ac_on\",\"tempC\":40}");
        assertEquals("3", cmdHigh.id);
        assertEquals("ac_on", cmdHigh.action);
        assertEquals(31, cmdHigh.tempC);

        // In-range tempC (e.g. 22) -> preserved
        WatchCommand cmdValid = WatchProtocol.parseCommand("{\"id\":\"4\",\"action\":\"ac_on\",\"tempC\":22}");
        assertEquals("4", cmdValid.id);
        assertEquals("ac_on", cmdValid.action);
        assertEquals(22, cmdValid.tempC);
    }

    @Test
    public void parseRejectsMalformedJsonAndUnknownAction() {
        // Null or empty
        try {
            WatchProtocol.parseCommand(null);
            fail("Expected IllegalArgumentException for null payload");
        } catch (IllegalArgumentException expected) {}

        try {
            WatchProtocol.parseCommand("");
            fail("Expected IllegalArgumentException for empty payload");
        } catch (IllegalArgumentException expected) {}

        // Invalid JSON string
        try {
            WatchProtocol.parseCommand("not_json");
            fail("Expected IllegalArgumentException for invalid JSON");
        } catch (IllegalArgumentException expected) {}

        // Empty JSON object
        try {
            WatchProtocol.parseCommand("{}");
            fail("Expected IllegalArgumentException for missing id and action");
        } catch (IllegalArgumentException expected) {}

        // Missing action
        try {
            WatchProtocol.parseCommand("{\"id\":\"1\"}");
            fail("Expected IllegalArgumentException for missing action");
        } catch (IllegalArgumentException expected) {}

        // Missing id
        try {
            WatchProtocol.parseCommand("{\"action\":\"lock\"}");
            fail("Expected IllegalArgumentException for missing id");
        } catch (IllegalArgumentException expected) {}

        // Unknown action
        try {
            WatchProtocol.parseCommand("{\"id\":\"1\",\"action\":\"explode\"}");
            fail("Expected IllegalArgumentException for unknown action");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void parsesAllValidActions() {
        String[] actions = new String[]{"status", "lock", "unlock", "ac_on", "ac_off", "lights", "horn"};
        for (int i = 0; i < actions.length; i++) {
            String act = actions[i];
            WatchCommand cmd = WatchProtocol.parseCommand("{\"id\":\"" + (i + 1) + "\",\"action\":\"" + act + "\"}");
            assertEquals(String.valueOf(i + 1), cmd.id);
            assertEquals(act, cmd.action);
        }
    }

    @Test
    public void serializesOkAndErrorResponsesMatchingContract() throws Exception {
        // 1. Success response matching design contract
        VehicleStatus status = new VehicleStatus(78, 290, true, false, 27, 17, 1700000002L);
        String okJsonStr = WatchProtocol.ok("1700000001", "status", "Updated", status);
        JSONObject okJson = new JSONObject(okJsonStr);

        assertEquals("1700000001", okJson.getString("id"));
        assertTrue(okJson.getBoolean("ok"));
        assertEquals("status", okJson.getString("action"));
        assertEquals("Updated", okJson.getString("msg"));
        assertFalse("Success response must not have err key", okJson.has("err"));
        assertTrue("Success response must have state key", okJson.has("state"));

        JSONObject stateObj = okJson.getJSONObject("state");
        assertEquals(78, stateObj.getInt("soc"));
        assertEquals(290, stateObj.getInt("rangeKm"));
        assertTrue(stateObj.getBoolean("locked"));
        assertFalse(stateObj.getBoolean("acOn"));
        assertEquals(27, stateObj.getInt("cabinTempC"));
        assertEquals(17, stateObj.getInt("targetTempC"));
        assertEquals(1700000002L, stateObj.getLong("ts"));

        // 2. Error response matching design contract
        String errJsonStr = WatchProtocol.error("1700000002", "lock", "PIN_INVALID", "Invalid control PIN");
        JSONObject errJson = new JSONObject(errJsonStr);

        assertEquals("1700000002", errJson.getString("id"));
        assertFalse(errJson.getBoolean("ok"));
        assertEquals("lock", errJson.getString("action"));
        assertEquals("PIN_INVALID", errJson.getString("err"));
        assertEquals("Invalid control PIN", errJson.getString("msg"));
        assertFalse("Error response must not have state key", errJson.has("state"));
    }

    @Test
    public void vehicleStatusHandlesNullCabinTemp() throws Exception {
        VehicleStatus status = new VehicleStatus(50, 150, false, true, null, 18, 1700000010L);
        JSONObject json = status.toJson();
        assertEquals(50, json.getInt("soc"));
        assertEquals(150, json.getInt("rangeKm"));
        assertFalse(json.getBoolean("locked"));
        assertTrue(json.getBoolean("acOn"));
        assertTrue(json.isNull("cabinTempC"));
        assertEquals(18, json.getInt("targetTempC"));
        assertEquals(1700000010L, json.getLong("ts"));

        VehicleStatus roundTrip = VehicleStatus.fromJson(json);
        assertNull(roundTrip.cabinTempC);
        assertEquals(50, roundTrip.soc);
        assertEquals(150, roundTrip.rangeKm);
        assertFalse(roundTrip.locked);
        assertTrue(roundTrip.acOn);
        assertEquals(18, roundTrip.targetTempC);
        assertEquals(1700000010L, roundTrip.ts);
    }

    @Test
    public void parsesExtendedActionsAndExtendedVehicleStatusRoundTrip() throws Exception {
        String[] extra = new String[]{"vent_windows", "close_windows", "bat_heat_on", "bat_heat_off", "seat_heat", "switch_car"};
        for (int i = 0; i < extra.length; i++) {
            WatchCommand cmd = WatchProtocol.parseCommand("{\"id\":\"e" + i + "\",\"action\":\"" + extra[i] + "\",\"vin\":\"VIN99\",\"level\":2}");
            assertEquals(extra[i], cmd.action);
            assertEquals("VIN99", cmd.vin);
            assertEquals(2, cmd.level);
        }

        java.util.List<VehicleSummary> cars = java.util.Arrays.asList(
                new VehicleSummary("VIN_A", "Seal U", "B1", "EV"),
                new VehicleSummary("VIN_B", "Atto 1", "B2", "EV")
        );
        VehicleStatus rich = new VehicleStatus.Builder(84, 342, true, false, 23, 21, 1700000500L)
                .vin("VIN_A")
                .modelName("Seal U")
                .chargingState(VehicleStatus.CHARGING_ACTIVE)
                .chargeRemainingMinutes(45)
                .doorsOpenCount(1)
                .windowsOpenCount(2)
                .trunkOpen(true)
                .totalMileageKm(12450)
                .outsideTempC(29)
                .fuelPercent(60)
                .fuelRangeKm(500)
                .tirePressuresKpa(250, 248, 240, 242)
                .batteryHeatOn(Boolean.TRUE)
                .driverSeatLevel(2)
                .capabilities(true, true, true)
                .units("mi", "F", "PSI")
                .vehicles(cars)
                .build();

        VehicleStatus decoded = VehicleStatus.fromJson(rich.toJson());
        assertEquals("VIN_A", decoded.vin);
        assertEquals("Seal U", decoded.modelName);
        assertEquals(VehicleStatus.CHARGING_ACTIVE, decoded.chargingState);
        assertEquals(Integer.valueOf(45), decoded.chargeRemainingMinutes);
        assertEquals(1, decoded.doorsOpenCount);
        assertEquals(2, decoded.windowsOpenCount);
        assertTrue(decoded.trunkOpen);
        assertEquals(Integer.valueOf(12450), decoded.totalMileageKm);
        assertEquals(Integer.valueOf(29), decoded.outsideTempC);
        assertEquals(Integer.valueOf(60), decoded.fuelPercent);
        assertEquals(Integer.valueOf(500), decoded.fuelRangeKm);
        assertEquals(Integer.valueOf(250), decoded.tireLfKpa);
        assertEquals(Integer.valueOf(242), decoded.tireRrKpa);
        assertEquals(Boolean.TRUE, decoded.batteryHeatOn);
        assertEquals(2, decoded.driverSeatLevel);
        assertTrue(decoded.canWindows);
        assertTrue(decoded.canBatteryHeat);
        assertTrue(decoded.canSeatClimate);
        assertEquals("mi", decoded.distanceUnit);
        assertEquals("F", decoded.tempUnit);
        assertEquals("PSI", decoded.pressureUnit);
        assertEquals(2, decoded.vehicles.size());
        assertEquals("VIN_B", decoded.vehicles.get(1).vin);
    }

    @Test
    public void unitConversionsAndRegionsWorkCorrectly() {
        assertEquals("100 km", UnitPreferences.formatDistance(100, "km"));
        assertEquals("62 mi", UnitPreferences.formatDistance(100, "mi"));
        assertEquals("20°C", UnitPreferences.formatTemp(20, "C"));
        assertEquals("68°F", UnitPreferences.formatTemp(20, "F"));
        assertEquals("250 kPa", UnitPreferences.formatPressure(250, "kPa"));
        assertEquals("36.3 PSI", UnitPreferences.formatPressure(250, "PSI"));
        assertEquals("2.50 bar", UnitPreferences.formatPressure(250, "bar"));
        assertEquals(Integer.valueOf(245), UnitPreferences.normalizeRawTpmsToKpa(2.45));
        assertEquals(Integer.valueOf(269), UnitPreferences.normalizeRawTpmsToKpa(39.0));
        assertEquals("39.0 PSI", UnitPreferences.formatPressure(UnitPreferences.normalizeRawTpmsToKpa(39.0), "PSI"));
        assertEquals(Integer.valueOf(245), UnitPreferences.normalizeRawTpmsToKpa(245.0));

        assertEquals(17, RegionOption.ALL_REGIONS.size());
        assertEquals("cn", RegionOption.extractCodeFromText("China Mainland (CN)"));
        assertEquals("eu", RegionOption.extractCodeFromText("Europe (EU)"));
    }
}
