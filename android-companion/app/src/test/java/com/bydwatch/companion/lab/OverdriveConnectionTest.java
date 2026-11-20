package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OverdriveConnectionTest {

    @Test
    public void buildsPublicStatusUrl() {
        assertEquals("http://192.168.1.50:8080/auth/status",
                OverdriveConnection.statusUrl("192.168.1.50"));
        assertEquals("http://192.168.1.50:8080/auth/status",
                OverdriveConnection.statusUrl("http://192.168.1.50:8080"));
    }

    @Test
    public void blankAddressHasNoStatusUrl() {
        assertEquals("", OverdriveConnection.statusUrl(""));
        assertEquals("", OverdriveConnection.statusUrl(null));
    }

    @Test
    public void buildsAuthenticatedStatusUrl() {
        assertEquals("http://192.168.1.50:8080/status",
                OverdriveConnection.authStatusUrl("192.168.1.50"));
        assertEquals("", OverdriveConnection.authStatusUrl(""));
    }
}
