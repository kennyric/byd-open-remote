package com.overdrive.app.byd.cloud;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Allowlist for the trial Lab's raw endpoint runner.
 *
 * <p>Only the smart-charge configuration family and three read-only probes are
 * reachable. Physical command endpoints (remote control, OTA, Bluetooth,
 * standby vehicles) are deliberately excluded so the Lab can never bypass the
 * normal PIN and driving checks.
 */
public final class LabEndpointPolicy {

    private static final Set<String> READ_ONLY_PROBES = new HashSet<>(Arrays.asList(
            "/control/getBookingList",
            "/control/getStatusNow",
            "/control/getGpsInfo"));

    private LabEndpointPolicy() {}

    public static boolean isAllowed(String endpoint) {
        if (endpoint == null) return false;
        String path = endpoint.trim();
        if (path.isEmpty() || !path.startsWith("/")) return false;
        if (path.contains("..") || path.contains("://") || path.contains("\\")) return false;
        if (READ_ONLY_PROBES.contains(path)) return true;
        return path.matches("^/control/smartCharge/[A-Za-z0-9]+$");
    }
}
