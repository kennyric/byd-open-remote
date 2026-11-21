package com.bydwatch.companion;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Configuration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Locale;

/** Proves the Indonesian resources actually win when the device locale is Indonesian. */
@RunWith(RobolectricTestRunner.class)
public class IndonesianResourcesTest {

    @Test
    public void overdriveStringsResolveInIndonesian() {
        Context context = inIndonesian(RuntimeEnvironment.getApplication());

        assertEquals("Batas arus AC", context.getString(R.string.overdrive_current_title));
        assertEquals("Jadwal pengisian daya", context.getString(R.string.overdrive_schedule_title));
        assertEquals("Kode akses", context.getString(R.string.overdrive_access_code_label));
        assertEquals("Perlu login", context.getString(R.string.overdrive_status_login_needed));
        assertEquals("Lanjutan", context.getString(R.string.advanced_title));
    }

    @Test
    public void garageAndBiometricStringsResolveInIndonesian() {
        Context context = inIndonesian(RuntimeEnvironment.getApplication());

        assertEquals("Garasi", context.getString(R.string.garage_title));
        assertEquals("Keamanan Biometrik / PIN", context.getString(R.string.section_biometric_security));
        assertEquals("Buka Kunci", context.getString(R.string.btn_unlock_app));
    }

    private static Context inIndonesian(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(new Locale("in", "ID"));
        return base.createConfigurationContext(configuration);
    }
}
