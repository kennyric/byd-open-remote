package com.overdrive.app.byd.cloud;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CloudCallTraceTest {

    @Test
    public void successRequiresZeroCodeAndNoError() {
        JSONObject empty = new JSONObject();
        assertTrue(new CloudCallTrace("/x", empty, empty, empty, "0", "", 1L, null).isSuccess());
        assertFalse(new CloudCallTrace("/x", empty, empty, empty, "1001", "no", 1L, null).isSuccess());
        assertFalse(new CloudCallTrace("/x", empty, empty, empty, "0", "", 1L, "boom").isSuccess());
    }

    @Test
    public void shareTextMasksVinButConsoleTextDoesNot() throws Exception {
        JSONObject request = new JSONObject().put("vin", "LGXCE6CB0P0000001");
        CloudCallTrace trace = new CloudCallTrace(
                "/control/smartCharge/getAcCurrentLimit",
                request, new JSONObject(), new JSONObject(), "0", "", 1L, null);

        assertTrue(trace.consoleText().contains("LGXCE6CB0P0000001"));
        assertFalse(trace.shareText().contains("LGXCE6CB0P0000001"));
        assertTrue(trace.shareText().contains("0001"));
        assertEquals("0001", trace.maskedVin());
    }
}
