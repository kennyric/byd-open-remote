package com.bydwatch.companion.lab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
