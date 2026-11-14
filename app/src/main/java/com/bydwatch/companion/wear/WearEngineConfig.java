package com.bydwatch.companion.wear;

import com.bydwatch.companion.BuildConfig;

/**
 * Configuration parameters for Huawei Wear Engine P2P communication.
 */
public final class WearEngineConfig {

    public static final String DEFAULT_WATCH_PACKAGE_NAME = BuildConfig.WATCH_PACKAGE_NAME;
    public static final String DEFAULT_WATCH_CERT_FINGERPRINT = BuildConfig.WATCH_CERT_FINGERPRINT;
    public static final String STORE_WATCH_PACKAGE_NAME = BuildConfig.STORE_WATCH_PACKAGE_NAME;
    public static final String STORE_WATCH_CERT_FINGERPRINT = BuildConfig.STORE_WATCH_CERT_FINGERPRINT;

    private final String watchPackageName;
    private final String watchCertFingerprint;

    public WearEngineConfig() {
        this(DEFAULT_WATCH_PACKAGE_NAME, DEFAULT_WATCH_CERT_FINGERPRINT);
    }

    public static WearEngineConfig forTarget(String target) {
        if ("store".equalsIgnoreCase(target)) {
            return new WearEngineConfig(STORE_WATCH_PACKAGE_NAME, STORE_WATCH_CERT_FINGERPRINT);
        }
        return new WearEngineConfig(DEFAULT_WATCH_PACKAGE_NAME, DEFAULT_WATCH_CERT_FINGERPRINT);
    }

    public WearEngineConfig(String watchPackageName, String watchCertFingerprint) {
        this.watchPackageName = watchPackageName != null ? watchPackageName.trim() : "";
        this.watchCertFingerprint = watchCertFingerprint != null ? watchCertFingerprint.trim() : "";
    }

    public String getWatchPackageName() {
        return watchPackageName;
    }

    public String getWatchCertFingerprint() {
        return watchCertFingerprint;
    }

    public boolean hasFingerprint() {
        return !watchCertFingerprint.isEmpty();
    }
}
