package com.bydwatch.companion.lab;

import java.net.URI;

/** Normalizes the Overdrive head-unit URL entered in the Car Web screen. */
public final class CarWebUrl {

    public static final int DEFAULT_PORT = 8080;

    private CarWebUrl() {}

    public static String normalize(String input) {
        if (input == null) return "";
        String text = input.trim();
        if (text.isEmpty()) return "";
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            text = "http://" + text;
        }
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return "";
            String host = uri.getHost();
            if (host == null || host.isEmpty()) return "";
            int port = uri.getPort() >= 0 ? uri.getPort() : DEFAULT_PORT;
            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            return new URI(scheme, uri.getUserInfo(), host, port, path,
                    uri.getQuery(), uri.getFragment()).toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static String fromGateway(String gateway) {
        if (gateway == null || gateway.trim().isEmpty()) return "";
        return normalize(gateway.trim() + ":" + DEFAULT_PORT);
    }
}
