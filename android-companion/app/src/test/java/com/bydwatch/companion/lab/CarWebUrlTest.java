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
}
