package com.bydwatch.companion.wear;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WearEngineConfigTest {

    @Test
    public void defaultValuesMatchBuildConfig() {
        WearEngineConfig config = new WearEngineConfig();
        assertEquals("com.bydwatch.watch", config.getWatchPackageName());
        assertEquals("", config.getWatchCertFingerprint());
        assertFalse(config.hasFingerprint());
    }

    @Test
    public void customValuesArePreserved() {
        WearEngineConfig config = new WearEngineConfig("com.custom.watch", "AA:BB:CC:DD");
        assertEquals("com.custom.watch", config.getWatchPackageName());
        assertEquals("AA:BB:CC:DD", config.getWatchCertFingerprint());
        assertTrue(config.hasFingerprint());
    }

    @Test
    public void handlesNullGracefully() {
        WearEngineConfig config = new WearEngineConfig(null, null);
        assertEquals("", config.getWatchPackageName());
        assertEquals("", config.getWatchCertFingerprint());
        assertFalse(config.hasFingerprint());
    }
}
