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
