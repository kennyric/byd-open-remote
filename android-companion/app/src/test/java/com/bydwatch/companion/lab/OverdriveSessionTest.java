package com.bydwatch.companion.lab;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverdriveSessionTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @After
    public void tearDown() {
        CarWebPrefs.reset(context);
    }

    @Test
    public void startsEmpty() {
        assertFalse(OverdriveSession.hasAccessCode(context));
        assertNull(OverdriveSession.jwt(context));
        assertFalse(OverdriveSession.hasJwt(context, 0L));
    }

    @Test
    public void storesAccessCodeTrimmed() {
        OverdriveSession.setAccessCode(context, "  X7K9M2P5 ");
        assertEquals("X7K9M2P5", OverdriveSession.accessCode(context));
        assertTrue(OverdriveSession.hasAccessCode(context));
    }

    @Test
    public void rejectsShortAccessCode() {
        assertFalse(OverdriveSession.isPlausibleAccessCode("short"));
        assertTrue(OverdriveSession.isPlausibleAccessCode("x7k9m2p5"));
    }

    @Test
    public void storesJwtWithExpiry() {
        long now = 1_700_000_000_000L;
        OverdriveSession.setJwt(context, "header.payload.sig", 3600L, now);
        assertEquals("header.payload.sig", OverdriveSession.jwt(context));
        assertTrue(OverdriveSession.hasJwt(context, now + 1000L));
        assertFalse(OverdriveSession.hasJwt(context, now + 3601L * 1000L));
    }

    @Test
    public void clearForgetsEverything() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        OverdriveSession.setDeviceId(context, "byd-a1b2c3d4");
        OverdriveSession.setJwt(context, "a.b.c", 3600L, 1L);
        OverdriveSession.clear(context);
        assertFalse(OverdriveSession.hasAccessCode(context));
        assertNull(OverdriveSession.deviceId(context));
        assertNull(OverdriveSession.jwt(context));
    }

    @Test
    public void clearJwtKeepsAccessCode() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        OverdriveSession.setJwt(context, "a.b.c", 3600L, 1L);
        OverdriveSession.clearJwt(context);
        assertNull(OverdriveSession.jwt(context));
        assertTrue(OverdriveSession.hasAccessCode(context));
    }

    @Test
    public void carWebResetAlsoForgetsSession() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");
        CarWebPrefs.reset(context);
        assertFalse(OverdriveSession.hasAccessCode(context));
    }
}
