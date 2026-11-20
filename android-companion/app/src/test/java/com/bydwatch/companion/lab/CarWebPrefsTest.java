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
        assertEquals("http://192.168.1.50:8080", CarWebPrefs.baseUrl(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
    }

    @Test
    public void shortcutsDefaultOffAndPersist() {
        assertFalse(CarWebPrefs.overdriveShortcutsEnabled(context));
        CarWebPrefs.setOverdriveShortcutsEnabled(context, true);
        assertTrue(CarWebPrefs.overdriveShortcutsEnabled(context));
    }
}
