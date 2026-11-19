package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LabVerdictTest {

    @Test
    public void appliedWhenReReadMatchesRequested() {
        assertEquals(LabVerdict.APPLIED,
                LabVerdict.classify("MAX", "10A", "10A", "0", null, true));
        assertEquals(LabVerdict.APPLIED,
                LabVerdict.classify("MAX", "10", "10A", "0", null, true));
    }

    @Test
    public void unchangedAndChangedAreDistinct() {
        assertEquals(LabVerdict.UNCHANGED,
                LabVerdict.classify("MAX", "10A", "MAX", "0", null, true));
        assertEquals(LabVerdict.CHANGED,
                LabVerdict.classify("MAX", "10A", "16A", "0", null, true));
    }

    @Test
    public void pendingWhenNoReReadHappened() {
        assertEquals(LabVerdict.PENDING,
                LabVerdict.classify("MAX", "10A", null, "0", null, false));
    }

    @Test
    public void rejectsAndErrorsAreReportedFirst() {
        assertEquals(LabVerdict.REJECTED,
                LabVerdict.classify("MAX", "10A", "MAX", "1001", null, true));
        assertEquals(LabVerdict.UNREACHABLE,
                LabVerdict.classify("MAX", "10A", "MAX", "6002", null, true));
        assertEquals(LabVerdict.ERROR,
                LabVerdict.classify("MAX", "10A", "MAX", "0", "network", true));
    }
}
