package com.bydwatch.companion.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Supported BYD Cloud regional endpoints matching {@link com.overdrive.app.byd.cloud.BydCloudRegionCatalog}.
 */
public final class RegionOption {

    public final String code;
    public final String displayName;

    public RegionOption(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String formatLabel() {
        return displayName + " (" + code.toUpperCase(Locale.US) + ")";
    }

    @Override
    public String toString() {
        return formatLabel();
    }

    public static final List<RegionOption> ALL_REGIONS = Collections.unmodifiableList(Arrays.asList(
            new RegionOption("id", "Indonesia"),
            new RegionOption("eu", "Europe"),
            new RegionOption("sg", "Singapore / SEA"),
            new RegionOption("au", "Australia / NZ"),
            new RegionOption("br", "Brazil"),
            new RegionOption("mx", "Mexico / LATAM"),
            new RegionOption("jp", "Japan"),
            new RegionOption("kr", "South Korea"),
            new RegionOption("in", "India"),
            new RegionOption("vn", "Vietnam / Thailand"),
            new RegionOption("tr", "Turkey"),
            new RegionOption("sa", "Saudi Arabia"),
            new RegionOption("om", "Oman"),
            new RegionOption("no", "Norway"),
            new RegionOption("uz", "Uzbekistan"),
            new RegionOption("kz", "Kazakhstan"),
            new RegionOption("cn", "China Mainland")
    ));

    public static RegionOption findByCode(String code) {
        if (code != null) {
            String clean = code.trim().toLowerCase(Locale.US);
            for (RegionOption opt : ALL_REGIONS) {
                if (opt.code.equals(clean)) {
                    return opt;
                }
            }
        }
        return ALL_REGIONS.get(0);
    }

    public static String extractCodeFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "id";
        }
        String trimmed = text.trim();
        for (RegionOption opt : ALL_REGIONS) {
            if (opt.formatLabel().equalsIgnoreCase(trimmed) || opt.code.equalsIgnoreCase(trimmed)) {
                return opt.code;
            }
        }
        int open = trimmed.lastIndexOf('(');
        int close = trimmed.lastIndexOf(')');
        if (open >= 0 && close > open + 1) {
            String inside = trimmed.substring(open + 1, close).trim().toLowerCase(Locale.US);
            for (RegionOption opt : ALL_REGIONS) {
                if (opt.code.equals(inside)) {
                    return opt.code;
                }
            }
        }
        return trimmed.toLowerCase(Locale.US);
    }
}
