package com.overdrive.app.byd.cloud;

import java.util.Locale;

/**
 * Canonicalization for AC charging current values.
 *
 * <p>The cloud may return {@code "10A"} while a person types {@code "10"};
 * both mean the same limit. {@link #canonical(String)} produces one spelling
 * for comparison and {@link #same(String, String)} compares two values.
 */
public final class ChargeLimitValues {

    private ChargeLimitValues() {}

    public static String canonical(String value) {
        if (value == null) return "";
        String text = value.trim().toUpperCase(Locale.US).replaceAll("\\s+", "");
        if (text.isEmpty()
                || "-".equals(text)
                || "--".equals(text)
                || "NULL".equals(text)
                || "UNKNOWN".equals(text)) {
            return "";
        }
        if (text.matches("\\d+")) {
            return text + "A";
        }
        return text;
    }

    public static boolean same(String a, String b) {
        String left = canonical(a);
        String right = canonical(b);
        return !left.isEmpty() && left.equals(right);
    }
}
