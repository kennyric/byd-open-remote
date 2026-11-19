package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabGateTest {
    @Test
    public void enabledOnlyForDebugNonDemoBuilds() {
        assertTrue(LabGate.isEnabled(true, false));
        assertFalse(LabGate.isEnabled(false, false));
        assertFalse(LabGate.isEnabled(true, true));
        assertFalse(LabGate.isEnabled(false, true));
    }
}
