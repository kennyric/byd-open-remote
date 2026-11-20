package com.bydwatch.companion.lab;

import android.content.Context;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdvancedActivityTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        CarWebPrefs.reset(context);
    }

    @Test
    public void savingTopPagePersistsAndFinishes() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        SwitchMaterial show = activity.findViewById(
                com.bydwatch.companion.R.id.switchAdvShowOverdrive);
        show.setChecked(true);

        android.widget.RadioButton vehicle = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvVehicleControl);
        vehicle.setChecked(true);

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertTrue(CarWebPrefs.showOverdrive(context));
        assertEquals("/vehicle-control.html", CarWebPrefs.defaultPage(context));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void manualUrlOverridesPreset() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        android.widget.EditText manual = activity.findViewById(
                com.bydwatch.companion.R.id.etAdvManualUrl);
        manual.setText("http://192.168.1.50:8080/events.html");

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvSave).performClick();

        assertEquals("http://192.168.1.50:8080/events.html",
                CarWebPrefs.defaultPage(context));
    }

    @Test
    public void loadAllRevealsMorePages() {
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();
        android.view.View more = activity.findViewById(
                com.bydwatch.companion.R.id.layoutAdvMorePages);
        assertEquals(android.view.View.GONE, more.getVisibility());

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvLoadAll).performClick();
        assertEquals(android.view.View.VISIBLE, more.getVisibility());
    }

    @Test
    public void resetSelectsVehicleControl() {
        CarWebPrefs.setDefaultPage(context, "/events.html");
        AdvancedActivity activity = Robolectric.buildActivity(AdvancedActivity.class)
                .setup().get();

        activity.findViewById(com.bydwatch.companion.R.id.btnAdvReset).performClick();

        android.widget.RadioButton vehicle = activity.findViewById(
                com.bydwatch.companion.R.id.rbAdvVehicleControl);
        assertTrue(vehicle.isChecked());
    }
}
