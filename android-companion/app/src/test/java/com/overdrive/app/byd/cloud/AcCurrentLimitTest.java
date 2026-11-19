package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AcCurrentLimitTest {

    @Test
    public void parsesLiveEuPayload() throws Exception {
        JSONObject payload = new JSONObject()
                .put("currentLimitList", new JSONArray()
                        .put("6A").put("8A").put("10A").put("16A").put("MAX"))
                .put("currentEvCurrentLimit", "MAX");

        AcCurrentLimit limit = AcCurrentLimit.parse("VIN123", payload);

        assertEquals("VIN123", limit.vin);
        assertEquals("MAX", limit.active);
        assertEquals(Arrays.asList("6A", "8A", "10A", "16A", "MAX"), limit.options);
    }

    @Test
    public void toleratesDefensiveValues() throws Exception {
        JSONObject payload = new JSONObject()
                .put("currentLimitList", new JSONArray()
                        .put(6).put(" 10A ").put(JSONObject.NULL).put(""))
                .put("currentEvCurrentLimit", 16);

        AcCurrentLimit limit = AcCurrentLimit.parse("VIN123", payload);

        assertEquals("16", limit.active);
        assertEquals(Arrays.asList("6", "10A"), limit.options);

        AcCurrentLimit empty = AcCurrentLimit.parse("VIN123", new JSONObject());
        assertNull(empty.active);
        assertTrue(empty.options.isEmpty());

        AcCurrentLimit nullPayload = AcCurrentLimit.parse("VIN123", null);
        assertNull(nullPayload.active);
        assertTrue(nullPayload.options.isEmpty());
    }

    @Test
    public void canonicalizesAndComparesValues() {
        assertEquals("10A", ChargeLimitValues.canonical(" 10 "));
        assertEquals("10A", ChargeLimitValues.canonical("10a"));
        assertEquals("MAX", ChargeLimitValues.canonical(" max "));
        assertEquals("", ChargeLimitValues.canonical("--"));
        assertEquals("", ChargeLimitValues.canonical(null));
        assertTrue(ChargeLimitValues.same("10", "10A"));
        assertFalse(ChargeLimitValues.same("10A", "16A"));
    }
}
