package com.overdrive.app.byd.cloud;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class BydCloudClientAcCurrentLimitTest {

    private static class FakeClient extends BydCloudClient {
        String endpoint;
        JSONObject extra;
        CloudCallTrace nextTrace;

        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public CloudCallTrace callControlEndpoint(String endpoint, JSONObject extraInner) {
            this.endpoint = endpoint;
            this.extra = extraInner;
            return nextTrace;
        }
    }

    @Test
    public void readSendsVinAndEnergyTypeAndParsesResult() throws Exception {
        FakeClient client = new FakeClient();
        JSONObject decoded = new JSONObject()
                .put("currentLimitList", new JSONArray().put("6A").put("MAX"))
                .put("currentEvCurrentLimit", "MAX");
        client.nextTrace = new CloudCallTrace(
                "/control/smartCharge/getAcCurrentLimit",
                new JSONObject(), new JSONObject(), decoded, "0", "", 4L, null);

        BydCloudClient.AcCurrentLimitResult result =
                client.readAcCurrentLimit("VIN_TEST", "2");

        assertEquals("/control/smartCharge/getAcCurrentLimit", client.endpoint);
        assertEquals("VIN_TEST", client.extra.getString("vin"));
        assertEquals("2", client.extra.getString("energyType"));
        assertNotNull(result.limit);
        assertEquals("MAX", result.limit.active);
        assertEquals("MAX", result.limit.options.get(1));
    }

    @Test
    public void writeSendsLimitCurrentValue() throws Exception {
        FakeClient client = new FakeClient();
        client.nextTrace = new CloudCallTrace(
                "/control/smartCharge/limitCurrentControl",
                new JSONObject(), new JSONObject(), new JSONObject(), "0", "", 4L, null);

        BydCloudClient.AcCurrentLimitResult result =
                client.writeAcCurrentLimit("VIN_TEST", "10A");

        assertEquals("/control/smartCharge/limitCurrentControl", client.endpoint);
        assertEquals("VIN_TEST", client.extra.getString("vin"));
        assertEquals("10A", client.extra.getString("limitCurrentValue"));
        assertNull(result.limit);
    }

    @Test
    public void nonAllowlistedEndpointReturnsErrorWithoutNetwork() throws Exception {
        BydCloudClient client = new BydCloudClient(new BydCloudConfig());

        CloudCallTrace trace = client.callControlEndpoint(
                "/control/remoteControl", new JSONObject().put("vin", "VIN_TEST"));

        assertFalse(trace.isSuccess());
        assertNotNull(trace.error);
        assertEquals("endpoint not allowed", trace.error);
    }
}
