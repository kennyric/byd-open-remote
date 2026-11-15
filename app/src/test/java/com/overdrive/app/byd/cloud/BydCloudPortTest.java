package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodec;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodecFactory;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BydCloudPortTest {
    @Test
    public void indonesiaRegionResolvesToIdAndUsesBangcle() {
        assertEquals("ID", BydCloudRegionCatalog.defaultCountryForRegion("id"));
        assertFalse(BydCloudRegionCatalog.isChinaRegion("id"));
    }

    @Test
    public void configPreservesConfiguredLanguageWhenRegionUpdated() {
        BydCloudConfig cfg = new BydCloudConfig();
        cfg.setLanguage("en");
        cfg.setRegion("id");
        assertEquals("id", cfg.region);
        assertEquals("ID", cfg.countryCode);
        assertEquals("en", cfg.language);
    }

    @Test
    public void climateStartParamsMaps17CelsiusToRawTemp3() throws Exception {
        JSONObject params = BydCloudClient.climateStartParams(17.0);
        assertEquals(3, params.getInt("mainSettingTemp"));
        assertEquals(3, params.getInt("copilotSettingTemp"));
        assertEquals(1, params.getInt("airConditioningMode"));
        assertEquals(4, params.getInt("remoteMode"));
    }

    @Test
    public void climateStartElectricParamsBuildsEleAirPayloadWithGearAndFan() throws Exception {
        JSONObject params = BydCloudClient.climateStartElectricParams(3, 5, 10);
        assertEquals(3, params.getInt("mainSettingTemp"));
        assertEquals(3, params.getInt("copilotSettingTemp"));
        assertEquals(5, params.getInt("remoteMode"));
        assertEquals(2, params.getInt("airConditioningMode"));
        assertEquals(5, params.getInt("windLevel"));
        assertEquals(2, params.getInt("windMode"));
        assertEquals(2, params.getInt("acSwitch"));
        assertEquals(1, params.getInt("timeSpan"));
        assertEquals(1, params.getInt("airAccuracy"));

        JSONObject clamped = BydCloudClient.climateStartElectricParams(99, 0);
        assertEquals(9, clamped.getInt("mainSettingTemp"));
        assertEquals(4, clamped.getInt("windLevel"));

        JSONObject minClamped = BydCloudClient.climateStartElectricParams(-2, 12);
        assertEquals(1, minClamped.getInt("mainSettingTemp"));
        assertEquals(7, minClamped.getInt("windLevel"));

        JSONObject bodyOnly = BydCloudClient.climateStartElectricParams(3, 4, 1, 20);
        assertEquals(1, bodyOnly.getInt("windMode"));

        JSONObject feetOnly = BydCloudClient.climateStartElectricParams(5, 6, 3, 15);
        assertEquals(3, feetOnly.getInt("windMode"));
        assertEquals(2, feetOnly.getInt("timeSpan"));
    }

    @Test
    public void cloudCapabilitiesDetectsElectricAcForFunction1015OrAirTypeOne() throws Exception {
        JSONObject latest1015 = new JSONObject()
                .put("data", new org.json.JSONArray()
                        .put(new JSONObject().put("functionNo", "1015")));
        CloudCapabilities caps1015 = CloudCapabilities.fromResponses("VIN_ATTO1", latest1015, null, 1L);
        assertTrue(caps1015.supports(CloudCapabilities.Feature.CLIMATE));
        assertTrue(caps1015.supports(CloudCapabilities.Feature.ELECTRIC_CLIMATE));
        assertTrue(caps1015.isElectricAc());

        JSONObject latest1001 = new JSONObject()
                .put("data", new org.json.JSONArray()
                        .put(new JSONObject().put("functionNo", "1001")));
        CloudCapabilities capsAuto = CloudCapabilities.fromResponses("VIN_ATTO3", latest1001, null, 1L);
        assertTrue(capsAuto.supports(CloudCapabilities.Feature.CLIMATE));
        assertFalse(capsAuto.isElectricAc());

        JSONObject vehicleAirType1 = new JSONObject().put("airType", 1);
        CloudCapabilities capsAirType = CloudCapabilities.fromResponses("VIN_A1", latest1001, vehicleAirType1, 1L);
        assertTrue(capsAirType.isElectricAc());
    }

    @Test
    public void bangcleCodecInitializesFromAssetStream() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("byd/bangcle_tables.bin")) {
            assertNotNull(in);
            BydCloudConfig cfg = new BydCloudConfig();
            cfg.setRegion("id");
            BydCloudClient client = new BydCloudClient(cfg);
            client.init(in);
            assertTrue(client.isReady());
        }
    }

    @Test
    public void bangcleCodecEncryptsAndDecryptsPayloadRoundtrip() throws Exception {
        EnvelopeCodec codec = EnvelopeCodecFactory.createCodec(false);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("byd/bangcle_tables.bin")) {
            assertNotNull("bangcle_tables.bin must be on test classpath via assets", in);
            codec.loadTables(in);
            assertTrue(codec.isReady());

            String sampleJson = "{\"test\":\"value\",\"number\":123}";
            String envelope = codec.encodeEnvelope(sampleJson);
            assertNotNull(envelope);
            assertTrue(envelope.startsWith("F"));

            String decrypted = codec.decodeEnvelope(envelope);
            assertEquals(sampleJson, decrypted);
        }
    }

    @Test
    public void checkcodeCalculationComputesExpectedFormat() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("action", "test");
        payload.put("timestamp", "123456789");
        String checkcode = BydCryptoUtils.computeCheckcode(payload);
        assertNotNull(checkcode);
        assertEquals(32, checkcode.length());
    }
}
