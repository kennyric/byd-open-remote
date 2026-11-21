package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CarWebUrlTest {

    @Test
    public void addsSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.50:8080/", CarWebUrl.normalize("192.168.1.50"));
        assertEquals("http://192.168.1.50:8080/", CarWebUrl.normalize("http://192.168.1.50"));
        assertEquals("http://192.168.1.50:8080/", CarWebUrl.normalize("http://192.168.1.50:8080"));
    }

    @Test
    public void keepsExplicitPortAndPath() {
        assertEquals("http://192.168.1.50:9090/login", CarWebUrl.normalize("192.168.1.50:9090/login"));
        assertEquals("https://car.local:8080/", CarWebUrl.normalize("https://car.local"));
    }

    @Test
    public void rejectsEmptyOrInvalidInput() {
        assertEquals("", CarWebUrl.normalize(null));
        assertEquals("", CarWebUrl.normalize(""));
        assertEquals("", CarWebUrl.normalize("   "));
        assertEquals("", CarWebUrl.normalize("not a host"));
    }

    @Test
    public void originDropsPagePathAndQuery() {
        assertEquals("http://100.84.59.94:8080/",
                CarWebUrl.origin("http://100.84.59.94:8080/login.html?redirect=/index.html"));
        assertEquals("http://100.84.59.94:8080/",
                CarWebUrl.origin("100.84.59.94:8080/login.html?redirect=/index.html"));
        assertEquals("http://192.168.1.50:8080/",
                CarWebUrl.origin("192.168.1.50:8080/vehicle-control.html"));
        assertEquals("http://192.168.1.50:8080/", CarWebUrl.origin("192.168.1.50"));
        assertEquals("", CarWebUrl.origin(""));
        assertEquals("", CarWebUrl.origin("not a host"));
    }

    @Test
    public void entryUsesAPastedPageUrlExactlyAsEntered() {
        String pasted = "http://100.84.59.94:8080/login.html?redirect=/index.html";
        assertTrue(CarWebUrl.hasPage(pasted));
        assertEquals(pasted, CarWebUrl.entry(pasted, "/vehicle-control.html"));

        assertFalse(CarWebUrl.hasPage("http://100.84.59.94:8080/"));
        assertEquals("http://100.84.59.94:8080/vehicle-control.html",
                CarWebUrl.entry("100.84.59.94:8080", "/vehicle-control.html"));
    }

    @Test
    public void buildsFromGateway() {
        assertEquals("http://192.168.43.1:8080/", CarWebUrl.fromGateway("192.168.43.1"));
        assertEquals("", CarWebUrl.fromGateway(null));
        assertEquals("", CarWebUrl.fromGateway(""));
    }

    @Test
    public void resolvesRelativePagesAgainstBase() {
        assertEquals("http://192.168.1.50:8080/vehicle-control.html",
                CarWebUrl.resolve("192.168.1.50", "vehicle-control.html"));
        assertEquals("http://192.168.1.50:8080/events.html",
                CarWebUrl.resolve("http://192.168.1.50:8080", "/events.html"));
        assertEquals("http://192.168.1.50:8080/",
                CarWebUrl.resolve("http://192.168.1.50:8080", "/"));
    }

    @Test
    public void absolutePageOverridesBase() {
        assertEquals("http://10.0.0.5:9090/events.html",
                CarWebUrl.resolve("http://192.168.1.50:8080", "http://10.0.0.5:9090/events.html"));
    }

    @Test
    public void relativePageWithoutBaseIsEmpty() {
        assertEquals("", CarWebUrl.resolve("", "events.html"));
        assertEquals("", CarWebUrl.resolve(null, "events.html"));
    }

    @Test
    public void appendsSafeFragmentOnlyOnce() {
        assertEquals("http://x:8080/vehicle-control.html#panelCharging",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", "panelCharging"));
        assertEquals("http://x:8080/vehicle-control.html#acChargeCurrentSection",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", "acChargeCurrentSection"));
        assertEquals("http://x:8080/vehicle-control.html",
                CarWebUrl.withFragment("http://x:8080/vehicle-control.html", ""));
    }
}
