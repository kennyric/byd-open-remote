package com.bydwatch.companion.lab;

import android.content.Context;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepositoryTest;

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
    private BydRepositoryTest.InMemoryConfigStore configStore;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        BydWatchApp.setTestDependencies(configStore, null, null);
        CarWebPrefs.reset(context);
    }

    @After
    public void tearDown() {
        CarWebPrefs.reset(context);
        BydWatchApp.resetForTesting();
    }

    @Test
    public void accessCodeIsStoredEncryptedNotInPlainPrefs() {
        OverdriveSession.setAccessCode(context, "x7k9m2p5");

        assertEquals("x7k9m2p5", configStore.getOverdriveAccessCode());
        assertNull(context.getSharedPreferences("car_web", Context.MODE_PRIVATE)
                .getString("overdrive_access_code", null));
    }

    @Test
    public void legacyPlaintextSessionIsMigratedAndRemoved() {
        context.getSharedPreferences("car_web", Context.MODE_PRIVATE)
                .edit()
                .putString("overdrive_access_code", "legacy123")
                .putString("overdrive_jwt", "legacy-jwt")
                .putLong("overdrive_jwt_expiry", 1_800_000_000_000L)
                .putString("overdrive_device_id", "byd-legacy")
                .apply();

        assertEquals("legacy123", OverdriveSession.accessCode(context));
        assertEquals("byd-legacy", OverdriveSession.deviceId(context));
        assertTrue(OverdriveSession.hasJwt(context, 1_700_000_000_000L));
        assertNull(context.getSharedPreferences("car_web", Context.MODE_PRIVATE)
                .getString("overdrive_access_code", null));
        assertEquals("legacy123", configStore.getOverdriveAccessCode());
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
