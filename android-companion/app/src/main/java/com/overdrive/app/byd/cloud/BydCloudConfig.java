package com.overdrive.app.byd.cloud;

import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;

/**
 * BYD Cloud API configuration.
 */
public final class BydCloudConfig {

    private static final CloudLog logger = CloudLog.getInstance("BydCloudConfig");

    private static final String BASE_URL_PREFIX = "https://dilinkappoversea-";
    private static final String BASE_URL_SUFFIX = ".byd.auto";
    private static final String USER_AGENT = "okhttp/4.12.0";

    // ── China (CN) stack ────────────────────────────────────────────────
    private static final String CN_BASE_URL = "https://dilinksuperappserver-cn.byd.auto";
    public static final String CN_APP_CHANNEL = "99";
    public static final String CN_APP_VERSION = "9.10.2";
    public static final String CN_APP_INNER_VERSION = "502";
    public static final String CN_TARGET_BRAND = "1";   // 1 = dynasty
    public static final String CN_VEHICLE_BRAND = "1";
    public static final String CN_NETWORK_OPERATOR = "无";
    public static final String CN_BRAND_FLAG = "dynasty";

    public boolean enabled = true;
    public String username = "";
    public String loginKey = "";
    public String signPassword = "";
    public String commandPwd = "";
    public String rawPassword = "";
    public String vin = "";
    public String countryCode = "ID";
    public String language = "en";
    public String region = "id";        // Server region: eu, in, sg, au, br, id, etc.
    public String imeiMd5 = "00000000000000000000000000000000";
    public String appInnerVersion = "323";
    public String appVersion = "3.2.3";
    public boolean cloudDataMerge = false;
    public String energyType = "";

    public BydCloudConfig() {
        this("id", "ID", "en");
    }

    public BydCloudConfig(String region, String countryCode, String language) {
        setRegion(region != null ? region : "id");
        if (countryCode != null && !countryCode.isEmpty()) {
            this.countryCode = countryCode;
        }
        if (language != null && !language.isEmpty()) {
            this.language = language;
        }
    }

    public void setRegion(String region) {
        String normalizedRegion = BydCloudRegionCatalog.normalizeRegion(region);
        this.region = normalizedRegion;
        this.countryCode = BydCloudRegionCatalog.defaultCountryForRegion(normalizedRegion);
        this.language = BydCloudRegionCatalog.languageForCountryCode(this.countryCode);
        if (BydCloudRegionCatalog.isChinaRegion(this.region)) {
            this.appInnerVersion = CN_APP_INNER_VERSION;
            this.appVersion = CN_APP_VERSION;
        } else {
            this.appInnerVersion = "323";
            this.appVersion = "3.2.3";
        }
    }

    public void setCountryCode(String countryCode) {
        String normalizedCountryCode = BydCloudRegionCatalog.normalizeCountryCode(countryCode);
        if (!normalizedCountryCode.isEmpty() && BydCloudRegionCatalog.isSupportedCountryCode(normalizedCountryCode)) {
            this.countryCode = normalizedCountryCode;
            this.region = BydCloudRegionCatalog.regionForCountryCode(normalizedCountryCode);
            this.language = BydCloudRegionCatalog.languageForCountryCode(this.countryCode);
        } else {
            logger.warn("Unsupported country code: " + countryCode);
        }
    }

    public void setLanguage(String language) {
        if (language != null && !language.trim().isEmpty()) {
            this.language = language.trim();
        }
    }

    public void setUsername(String username) {
        this.username = username != null ? username : "";
        if (!this.username.isEmpty()) {
            this.imeiMd5 = BydCryptoUtils.md5Hex(this.username);
        } else {
            this.imeiMd5 = "00000000000000000000000000000000";
        }
    }

    public void setLoginKey(String loginKey) {
        this.loginKey = loginKey != null ? loginKey : "";
    }

    public void setSignPassword(String signPassword) {
        this.signPassword = signPassword != null ? signPassword : "";
    }

    public void setCommandPwd(String commandPwd) {
        this.commandPwd = commandPwd != null ? commandPwd : "";
    }

    public void setRawPassword(String rawPassword) {
        this.rawPassword = rawPassword != null ? rawPassword : "";
    }

    public void setVin(String vin) {
        this.vin = vin != null ? vin : "";
    }

    public void setEnergyType(String energyType) {
        this.energyType = energyType != null ? energyType : "";
    }

    public void setCloudDataMerge(boolean cloudDataMerge) {
        this.cloudDataMerge = cloudDataMerge;
    }

    /**
     * Check if all required credentials are configured.
     */
    public boolean isConfigured() {
        return enabled
                && !username.isEmpty()
                && !loginKey.isEmpty()
                && !signPassword.isEmpty()
                && !commandPwd.isEmpty();
    }

    /**
     * Check if credentials have been verified (login + VIN + PIN all succeeded).
     */
    public boolean isVerified() {
        return isConfigured() && !vin.isEmpty();
    }

    /** Whether this config uses the China (CN) DiLink stack. */
    public boolean isChinaRegion() {
        return BydCloudRegionCatalog.isChinaRegion(region);
    }

    public String getBaseUrl() {
        if (isChinaRegion()) {
            return CN_BASE_URL;
        }
        return BASE_URL_PREFIX + region + BASE_URL_SUFFIX;
    }

    public String getUserAgent() {
        return USER_AGENT;
    }
}
