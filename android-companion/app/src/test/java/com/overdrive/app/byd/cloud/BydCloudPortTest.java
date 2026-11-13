package com.overdrive.app.byd.cloud;

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
}
