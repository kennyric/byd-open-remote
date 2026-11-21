package com.bydwatch.companion.lab;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CarWebPrefsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void defaultsAreHiddenOverdriveAndHiddenUrlBar() {
        assertFalse(CarWebPrefs.showOverdrive(context));
        assertTrue(CarWebPrefs.hideUrlBar(context));
        assertEquals("", CarWebPrefs.baseUrl(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void settersPersist() {
        CarWebPrefs.setShowOverdrive(context, true);
        CarWebPrefs.setHideUrlBar(context, false);
        CarWebPrefs.setBaseUrl(context, "http://192.168.1.50:8080");
        CarWebPrefs.setDefaultPage(context, "/vehicle-control.html");

        assertTrue(CarWebPrefs.showOverdrive(context));
        assertFalse(CarWebPrefs.hideUrlBar(context));
        assertEquals("http://192.168.1.50:8080/", CarWebPrefs.baseUrl(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void baseUrlKeepsOnlyTheServerRoot() {
        CarWebPrefs.setBaseUrl(context, "http://100.84.59.94:8080/login.html?redirect=/index.html");
        assertEquals("http://100.84.59.94:8080/", CarWebPrefs.baseUrl(context));
    }

    @Test
    public void legacyBaseWithPagePathReadsAsOrigin() {
        context.getSharedPreferences("car_web", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("car_base_url", "http://100.84.59.94:8080/login.html?redirect=/index.html")
                .commit();
        assertEquals("http://100.84.59.94:8080/", CarWebPrefs.baseUrl(context));
    }

    @Test
    public void controlsVisibilityDefaultsToHidden() {
        assertEquals(CarWebPrefs.CONTROLS_HIDDEN,
                CarWebPrefs.overdriveControlsVisibility(context));
        assertFalse(CarWebPrefs.overdriveControlsEnabled(context));
    }

    @Test
    public void controlsVisibilityPersistsEveryChoice() {
        for (String mode : new String[]{CarWebPrefs.CONTROLS_ALWAYS,
                CarWebPrefs.CONTROLS_CONNECTED, CarWebPrefs.CONTROLS_HIDDEN}) {
            CarWebPrefs.setOverdriveControlsVisibility(context, mode);
            assertEquals(mode, CarWebPrefs.overdriveControlsVisibility(context));
        }
        assertFalse(CarWebPrefs.overdriveControlsEnabled(context));
        CarWebPrefs.setOverdriveControlsVisibility(context, CarWebPrefs.CONTROLS_ALWAYS);
        assertTrue(CarWebPrefs.overdriveControlsEnabled(context));
    }

    @Test
    public void oldOnOffToggleIsReadAsOnlyWhenConnected() {
        context.getSharedPreferences("car_web", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("overdrive_shortcuts_enabled", true).commit();
        assertEquals(CarWebPrefs.CONTROLS_CONNECTED,
                CarWebPrefs.overdriveControlsVisibility(context));

        context.getSharedPreferences("car_web", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("overdrive_shortcuts_enabled", false).commit();
        assertEquals(CarWebPrefs.CONTROLS_HIDDEN,
                CarWebPrefs.overdriveControlsVisibility(context));
    }
}
