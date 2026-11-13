package com.bydwatch.companion.data;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SecureConfigStoreTest {

    private SecureConfigStore store;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        store = new SecureConfigStore(context);
        store.clear();
    }

    @Test
    public void defaultsRegionToIdAndDefaultTempTo17() {
        assertEquals("id", store.getRegion());
        assertEquals(17, store.getDefaultTempC());
        assertFalse(store.isConfigured());
    }

    @Test
    public void storesAndRetrievesAllFields() {
        store.setUsername("driver@example.com");
        store.setPassword("MyPassword");
        store.setPin("654321");
        store.setVin("LC0TEST1234567890");
        store.setRegion("eu");
        store.setDefaultTempC(20);

        assertEquals("driver@example.com", store.getUsername());
        assertEquals("MyPassword", store.getPassword());
        assertEquals("654321", store.getPin());
        assertEquals("LC0TEST1234567890", store.getVin());
        assertEquals("eu", store.getRegion());
        assertEquals(20, store.getDefaultTempC());
        assertTrue(store.isConfigured());

        store.clear();
        assertFalse(store.isConfigured());
        assertEquals("id", store.getRegion());
        assertEquals(17, store.getDefaultTempC());
    }
}
