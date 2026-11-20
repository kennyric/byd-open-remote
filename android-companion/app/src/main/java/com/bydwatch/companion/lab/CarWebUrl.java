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

    /**
     * Resolve a preset path against the configured car address.
     * An absolute page URL wins; a relative page with no base resolves to nothing.
     */
    public static String resolve(String base, String page) {
        String rawPage = page != null ? page.trim() : "";
        if (rawPage.startsWith("http://") || rawPage.startsWith("https://")) {
            return normalize(rawPage);
        }
        String normalizedBase = normalize(base);
        if (normalizedBase.isEmpty()) return "";
        try {
            URI baseUri = new URI(normalizedBase);
            String path = rawPage;
            while (path.startsWith("/")) path = path.substring(1);
            String query = null;
            int queryAt = path.indexOf('?');
            if (queryAt >= 0) {
                query = path.substring(queryAt + 1);
                path = path.substring(0, queryAt);
            }
            return new URI(baseUri.getScheme(), null, baseUri.getHost(), baseUri.getPort(),
                    "/" + path, query, null).toString();
        } catch (Exception e) {
            return "";
        }
    }
}
